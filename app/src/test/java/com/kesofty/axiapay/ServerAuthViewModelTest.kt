package com.kesofty.axiapay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.auth.ServerAccountAccess
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.presentation.auth.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Guards the real auth reducer against stale results, transient-secret persistence and recovery
 * regressions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerAuthViewModelTest {
    private val owner = ViewModelStore()
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val clock = MutableAuthenticationClock(now)
    private val api = RecordingAuthenticationRepository(now)
    private val store = MemoryAuthenticationSessionStore()
    private val handle = SavedStateHandle()

    /** Runs owned actions eagerly until their controlled suspension points. */
    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Releases all pending work and timers before restoring the global test dispatcher. */
    @After
    fun close() {
        owner.clear()
        Dispatchers.resetMain()
    }

    /** Ends the ViewModel owner before runTest drains timers, including assertion failures. */
    private fun withViewModelTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            owner.clear()
        }
    }

    /** Uses plain constructor assembly with the actual server workflow and isolated ports. */
    private fun viewModel() =
        AuthViewModel(
                handle,
                ServerAccountAccess(api, store, clock, RecordingWalletRepository()),
                clock,
            )
            .also {
                owner.put("auth", it)
            }

    /**
     * Real verification carries a server challenge and clears password/code state at the right
     * boundaries.
     */
    @Test
    fun passwordSignupClearsCredentialsAndResendRequiresExplicitPasswordReentry() =
        withViewModelTest {
            val vm = viewModel()
            vm.accept(AuthIntent.CreateAccount)
            vm.accept(AuthIntent.SelectContactType(AuthContactType.Email))
            vm.accept(AuthIntent.EditContact("ada@example.test"))
            vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
            vm.accept(AuthIntent.EditPassword(" private passphrase "))
            vm.accept(AuthIntent.Continue)
            val state = vm.uiState.value
            assertEquals(AuthScreen.Verification, state.screen)
            assertEquals("challenge-1", state.challenge?.id)
            assertEquals("", state.password)
            assertEquals(60L, state.resendSecondsRemaining)
            vm.accept(AuthIntent.ResendCode)
            assertEquals(1, api.codeRequests)
            clock.current = now.plusSeconds(61)
            advanceTimeBy(1000)
            runCurrent()
            vm.accept(AuthIntent.ResendCode)
            assertEquals(AuthScreen.CreateAccount, vm.uiState.value.screen)
            assertEquals(AuthError.PasswordAgain, vm.uiState.value.error)
            assertEquals("", vm.uiState.value.password)
            assertNull(vm.uiState.value.challenge)
            assertEquals(1, api.codeRequests)
            assertTrue(handle.keys().all { it == "auth.demoEntered" })
        }

    /** Failed proof remains on verification and never opens a dashboard or saves a token. */
    @Test
    fun rejectedCodeAndRateLimitsRemainRecoverableWithoutDemoFallback() = withViewModelTest {
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.Continue)
        api.failure = AuthenticationFailure(AccountAccessIssue.CodeRejected)
        vm.accept(AuthIntent.EditCode("001234"))
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.Verification, vm.uiState.value.screen)
        assertEquals(AuthError.CodeRejected, vm.uiState.value.error)
        assertNull(store.value)
        api.failure = AuthenticationFailure(AccountAccessIssue.RateLimited, 30)
        vm.accept(AuthIntent.Continue)
        assertEquals(30L, vm.uiState.value.retryAfterSeconds)
        api.failure = null
        vm.accept(AuthIntent.Continue)
        assertNull(store.value)
        clock.current = now.plusSeconds(31)
        advanceTimeBy(1000)
        runCurrent()
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.Complete, vm.uiState.value.screen)
        assertNotNull(vm.uiState.value.account)
        assertEquals("", vm.uiState.value.code)
        assertEquals("", vm.uiState.value.contact)
        assertFalse(vm.uiState.value.isDemo)
    }

    /**
     * Late login completion cannot replace navigation or persist credentials after cancellation.
     */
    @Test
    fun leavingPasswordSigninInvalidatesLateServerSuccess() = withViewModelTest {
        api.gate = CompletableDeferred()
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditPassword("passphrase"))
        vm.accept(AuthIntent.Continue)
        assertTrue(vm.uiState.value.isProcessing)
        vm.accept(AuthIntent.Back)
        api.gate!!.complete(Unit)
        runCurrent()
        assertEquals(AuthScreen.Welcome, vm.uiState.value.screen)
        assertNull(store.value)
        assertEquals("", vm.uiState.value.password)
    }

    /**
     * Connectivity failures preserve the token and require explicit recovery before real account
     * entry.
     */
    @Test
    fun sessionRecoveryAndLogoutFailuresDoNotClaimSuccess() = withViewModelTest {
        store.value =
            StoredAuthenticationSession(
                api.session.accessToken,
                now.plusSeconds(3600),
                api.apiIdentity,
            )
        api.failure = AuthenticationFailure(AccountAccessIssue.Network)
        val vm = viewModel()
        assertEquals(AuthScreen.SessionRecovery, vm.uiState.value.screen)
        assertNull(vm.uiState.value.account)
        assertNotNull(store.value)
        api.failure = null
        vm.accept(AuthIntent.RetrySession)
        assertEquals(AuthScreen.Complete, vm.uiState.value.screen)
        api.failure = AuthenticationFailure(AccountAccessIssue.Network)
        vm.accept(AuthIntent.SignOut)
        assertEquals(AuthScreen.Complete, vm.uiState.value.screen)
        assertNotNull(store.value)
        assertEquals(AuthError.Network, vm.uiState.value.error)
        api.failure = null
        vm.accept(AuthIntent.SignOut)
        assertEquals(AuthScreen.Welcome, vm.uiState.value.screen)
        assertNull(store.value)
    }

    /**
     * Opening account from an unauthenticated preview restores the account page, not the dashboard.
     */
    @Test
    fun accountShortcutUsesAccountRestorationDestination() = withViewModelTest {
        val vm = viewModel()
        vm.accept(AuthIntent.ExploreDemo)
        store.value =
            StoredAuthenticationSession(
                api.session.accessToken,
                now.plusSeconds(3600),
                api.apiIdentity,
            )
        vm.accept(AuthIntent.OpenAccount)
        assertEquals(AuthScreen.Complete, vm.uiState.value.screen)
        assertNotNull(vm.uiState.value.account)
        assertEquals(1, api.identityRequests)
    }

    /** Foreground checks never replace an unfinished form or automatically request another code. */
    @Test
    fun foregroundingDoesNotReplayCodeRequestsOrInterruptDrafts() = withViewModelTest {
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.EditCode("001234"))
        val before = vm.uiState.value
        vm.accept(AuthIntent.RefreshSession)
        assertEquals(before, vm.uiState.value)
        assertEquals(1, api.codeRequests)
        assertEquals(0, api.identityRequests)
    }
}

/** Test-owned adjustable wall clock for local presentation deadlines, never a production global. */
internal class MutableAuthenticationClock(var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)

    override fun instant(): Instant = current
}
