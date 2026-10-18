package com.kesofty.axiapay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.auth.ServerAccountAccess
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.wallet.*
import com.kesofty.axiapay.presentation.HomeWalletState
import com.kesofty.axiapay.presentation.auth.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Verifies wallet retrieval belongs to the successful session and never repeats authentication on
 * display failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountWalletTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val api = RecordingAuthenticationRepository(now)
    private val sessions = MemoryAuthenticationSessionStore()
    private val wallets = RecordingWalletRepository()
    private val access = ServerAccountAccess(api, sessions, clock, wallets)
    private val owner = ViewModelStore()
    private val login =
        AccountAccessInput(
            AuthContactType.Email,
            "ada@example.test",
            AuthMethod.Password,
            "private password",
        )

    /** Run ViewModel-owned actions eagerly until explicit controlled suspension. */
    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Dispose each owner before restoring the test dispatcher. */
    @After
    fun close() {
        owner.clear()
        Dispatchers.resetMain()
    }

    /**
     * Successful password login must persist credentials before issuing exactly one wallet read
     * with that bearer.
     */
    @Test
    fun passwordLoginFetchesOnlyAfterSessionPersistence() = runTest {
        wallets.beforeRead = { assertEquals(api.session.accessToken, sessions.value?.accessToken) }
        val result = access.begin(login) as AccountAccessResult.SignedIn
        assertEquals(WalletLookupResult.Loaded(wallets.wallet), result.wallet)
        assertEquals(api.session.accessToken, wallets.token)
        assertEquals("NGN", wallets.currency)
        assertEquals(1, wallets.requests)
        assertEquals(0, api.codeRequests)
    }

    /** Both signup and code-login challenges fetch nothing until successful proof redemption. */
    @Test
    fun signupAndCodeLoginFetchTheWalletAfterVerification() = runTest {
        for (signup in listOf(true, false)) {
            val challenge =
                access.begin(
                    login.copy(method = AuthMethod.OneTimeCode, isCreatingAccount = signup),
                ) as AccountAccessResult.CodeRequired
            val before = wallets.requests
            val result =
                access.verify(challenge.challenge, "001234") as AccountAccessResult.SignedIn
            assertEquals(before + 1, wallets.requests)
            assertEquals(api.session.account, result.account)
            assertEquals(WalletLookupResult.Loaded(wallets.wallet), result.wallet)
        }
    }

    /**
     * Failed credentials, rejected proof and failed protected writes cannot start a wallet request.
     */
    @Test
    fun failedAuthenticationOrSessionPersistenceNeverFetchesWallets() = runTest {
        api.failure = AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        try {
            access.begin(login)
            fail()
        } catch (expected: AuthenticationFailure) {
            assertEquals(AccountAccessIssue.InvalidCredentials, expected.issue)
        }
        api.failure = null
        sessions.writeFailure = true
        try {
            access.begin(login)
            fail()
        } catch (expected: AuthenticationFailure) {
            assertEquals(AccountAccessIssue.SecureStorage, expected.issue)
        }
        assertEquals(0, wallets.requests)
        assertNull(sessions.value)
    }

    /**
     * Display outages and absent wallets preserve successful login and recover through session
     * validation plus GET only.
     */
    @Test
    fun missingAndUnavailableWalletsDoNotUndoAuthentication() = runTest {
        for (issue in
            listOf(
                WalletIssue.Missing,
                WalletIssue.Network,
                WalletIssue.Unavailable,
                WalletIssue.InvalidResponse,
            )) {
            wallets.failure = WalletFailure(issue)
            val result = access.begin(login) as AccountAccessResult.SignedIn
            assertEquals(WalletLookupResult.Failed(issue), result.wallet)
            assertNotNull(sessions.value)
            val passwords = api.passwordRequests
            wallets.failure = null
            assertEquals(
                WalletLookupResult.Loaded(wallets.wallet),
                (access.restore() as AccountAccessResult.SignedIn).wallet,
            )
            assertEquals(passwords, api.passwordRequests)
            assertNull(api.code)
        }
    }

    /** A different wallet API cannot receive credentials captured for authentication. */
    @Test
    fun walletApiScopeAndCurrentSessionRemainBound() = runTest {
        wallets.apiIdentity = "https://other.example.test/v1/"
        val result = access.begin(login) as AccountAccessResult.SignedIn
        assertEquals(WalletLookupResult.Failed(WalletIssue.Configuration), result.wallet)
        assertEquals(0, wallets.requests)
        wallets.apiIdentity = api.apiIdentity
        val newer =
            StoredAuthenticationSession("b".repeat(43), now.plusSeconds(3600), api.apiIdentity)
        wallets.beforeRead = { sessions.value = newer }
        assertEquals(AccountAccessResult.SignedOut, access.restore())
        assertEquals(newer, sessions.value)
    }

    /**
     * Wallet 401 responses invalidate only the captured session instead of reporting a zero
     * balance.
     */
    @Test
    fun invalidWalletSessionClearsAccess() = runTest {
        wallets.failure = WalletFailure(WalletIssue.InvalidSession)
        assertEquals(AccountAccessResult.SignedOut, access.begin(login))
        assertNull(sessions.value)
    }

    /**
     * The reducer owns retry/loading state, drops credentials from UI and does not replay
     * login/proof.
     */
    @Test
    fun walletRetryAndSignOutUseExplicitOwnedState() = runTest {
        try {
            wallets.failure = WalletFailure(WalletIssue.Network)
            val handle = SavedStateHandle()
            val vm =
                AuthViewModel(handle, access, clock, enabledRemoteConfiguration()).also {
                    owner.put("auth", it)
                }
            vm.accept(AuthIntent.SignIn)
            vm.accept(AuthIntent.SelectContactType(AuthContactType.Email))
            vm.accept(AuthIntent.EditContact("ada@example.test"))
            vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
            vm.accept(AuthIntent.EditPassword("private password"))
            vm.accept(AuthIntent.Continue)
            assertEquals(AuthScreen.Dashboard, vm.uiState.value.screen)
            assertEquals(WalletLookupResult.Failed(WalletIssue.Network), vm.uiState.value.wallet)
            assertEquals("", vm.uiState.value.password)
            wallets.failure = null
            wallets.gate = CompletableDeferred()
            vm.accept(AuthIntent.RetryWallet)
            assertTrue(vm.uiState.value.isWalletRefreshing)
            assertTrue(vm.uiState.value.isProcessing)
            val requests = wallets.requests
            vm.accept(AuthIntent.RetryWallet)
            assertEquals(requests, wallets.requests)
            wallets.gate!!.complete(Unit)
            runCurrent()
            assertEquals(WalletLookupResult.Loaded(wallets.wallet), vm.uiState.value.wallet)
            assertFalse(vm.uiState.value.isWalletRefreshing)
            assertEquals(1, api.passwordRequests)
            assertEquals(0, api.verifyRequests)
            assertTrue(
                handle.keys().none { it.contains("wallet", true) || it.contains("token", true) },
            )
            vm.accept(AuthIntent.OpenAccount)
            vm.accept(AuthIntent.SignOut)
            assertNull(vm.uiState.value.wallet)
            assertNull(vm.uiState.value.account)
            assertNull(sessions.value)
        } finally {
            owner.clear()
        }
    }

    /** Home refresh uses the existing session, remains on Home and rejects duplicate taps. */
    @Test
    fun dashboardUsesAccountWalletAndRefreshKeepsItsRoute() = runTest {
        try {
            val vm = signedInViewModel()
            vm.accept(AuthIntent.OpenDashboard)
            assertEquals(AuthScreen.Dashboard, vm.uiState.value.screen)
            assertEquals(HomeWalletState.Loaded(wallets.wallet), vm.uiState.value.homeWallet)
            wallets.gate = CompletableDeferred()
            vm.accept(AuthIntent.RetryWallet)
            assertEquals(AuthScreen.Dashboard, vm.uiState.value.screen)
            assertEquals(HomeWalletState.Loading, vm.uiState.value.homeWallet)
            val requests = wallets.requests
            vm.accept(AuthIntent.RetryWallet)
            assertEquals(requests, wallets.requests)
            wallets.gate!!.complete(Unit)
            runCurrent()
            assertEquals(AuthScreen.Dashboard, vm.uiState.value.screen)
            assertEquals(HomeWalletState.Loaded(wallets.wallet), vm.uiState.value.homeWallet)
            assertEquals(1, api.passwordRequests)
            vm.accept(AuthIntent.OpenAccount)
            vm.accept(AuthIntent.SignOut)
            assertEquals(HomeWalletState.SignedOut, vm.uiState.value.homeWallet)
            assertNull(vm.uiState.value.wallet)
        } finally {
            owner.clear()
        }
    }

    /**
     * Home restoration reloads the captured owner's wallet without reading or reseeding demo money.
     */
    @Test
    fun restoredDashboardWaitsForTheRealWallet() = runTest {
        try {
            access.begin(login)
            wallets.gate = CompletableDeferred()
            val vm =
                AuthViewModel(
                        SavedStateHandle(mapOf("auth.demoEntered" to true)),
                        access,
                        clock,
                        enabledRemoteConfiguration(),
                    )
                    .also { owner.put("auth", it) }
            assertFalse(vm.uiState.value.homeWallet is HomeWalletState.Demo)
            assertFalse(vm.uiState.value.homeWallet is HomeWalletState.Loaded)
            wallets.gate!!.complete(Unit)
            runCurrent()
            assertEquals(AuthScreen.Dashboard, vm.uiState.value.screen)
            assertEquals(HomeWalletState.Loaded(wallets.wallet), vm.uiState.value.homeWallet)
        } finally {
            owner.clear()
        }
    }

    /** Failed refreshes and a later account never inherit the previous customer's balance. */
    @Test
    fun dashboardFailureAndAccountChangesClearEarlierAmounts() = runTest {
        try {
            val vm = signedInViewModel()
            vm.accept(AuthIntent.OpenDashboard)
            wallets.failure = WalletFailure(WalletIssue.Network)
            vm.accept(AuthIntent.RetryWallet)
            assertEquals(AuthScreen.Dashboard, vm.uiState.value.screen)
            assertEquals(HomeWalletState.Failed(WalletIssue.Network), vm.uiState.value.homeWallet)
            vm.accept(AuthIntent.OpenAccount)
            vm.accept(AuthIntent.SignOut)
            wallets.failure = null
            val earlier = wallets.wallet
            wallets.wallet =
                earlier.copy(
                    id = "123e4567-e89b-42d3-a456-426614174099",
                    availableMinorUnits = java.math.BigInteger.valueOf(900),
                    reservedMinorUnits = java.math.BigInteger.ZERO,
                    totalMinorUnits = java.math.BigInteger.valueOf(900),
                )
            api.session =
                api.session.copy(
                    accessToken = "b".repeat(43),
                    account = api.session.account.copy(id = "123e4567-e89b-42d3-a456-426614174098"),
                )
            passwordLogin(vm)
            vm.accept(AuthIntent.OpenDashboard)
            assertEquals(HomeWalletState.Loaded(wallets.wallet), vm.uiState.value.homeWallet)
            assertNotEquals(earlier, (vm.uiState.value.homeWallet as HomeWalletState.Loaded).wallet)
        } finally {
            owner.clear()
        }
    }

    /**
     * Build an owned server ViewModel and enter the verified account through public login intents.
     */
    private fun signedInViewModel(): AuthViewModel {
        val vm =
            AuthViewModel(SavedStateHandle(), access, clock, enabledRemoteConfiguration()).also {
                owner.put("auth", it)
            }
        passwordLogin(vm)
        return vm
    }

    /** Exercise the public password form without placing credentials in saved state. */
    private fun passwordLogin(vm: AuthViewModel) {
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.SelectContactType(AuthContactType.Email))
        vm.accept(AuthIntent.EditContact("ada@example.test"))
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditPassword("private password"))
        vm.accept(AuthIntent.Continue)
    }

    /**
     * A cancellation-resistant wallet response cannot replace newer navigation or enter saved-state
     * data.
     */
    @Test
    fun lateWalletResponsesCannotReplaceNavigation() = runTest {
        try {
            val vm =
                AuthViewModel(SavedStateHandle(), access, clock, enabledRemoteConfiguration())
                    .also { owner.put("auth", it) }
            vm.accept(AuthIntent.SignIn)
            vm.accept(AuthIntent.SelectContactType(AuthContactType.Email))
            vm.accept(AuthIntent.EditContact("ada@example.test"))
            vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
            vm.accept(AuthIntent.EditPassword("private password"))
            wallets.gate = CompletableDeferred()
            vm.accept(AuthIntent.Continue)
            assertNotNull(sessions.value)
            vm.accept(AuthIntent.Back)
            wallets.gate!!.complete(Unit)
            runCurrent()
            assertEquals(AuthScreen.Welcome, vm.uiState.value.screen)
            assertNull(vm.uiState.value.wallet)
        } finally {
            owner.clear()
        }
    }
}
