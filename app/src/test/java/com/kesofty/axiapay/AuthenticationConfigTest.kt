package com.kesofty.axiapay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.auth.ServerAccountAccess
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.config.*
import com.kesofty.axiapay.presentation.auth.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Auth reducer regressions for independent switches, config failures, stale results and 403
 * recovery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationConfigTest {
    private val owner = ViewModelStore()
    private val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)
    private val api = RecordingAuthenticationRepository(clock.instant())
    private var configuration =
        RemoteConfiguration(
            "v1",
            AuthenticationMethods(true, true),
            AuthenticationMethods(true, true),
        )
    private var cachedConfiguration: RemoteConfiguration? = null
    private var cachedReadGate: CompletableDeferred<Unit>? = null
    private var unavailable = false
    private var gate: CompletableDeferred<Unit>? = null
    private var refreshes = 0

    /** Eager reducer actions stop only at controlled IO gates. */
    @Before
    fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Cancels ViewModel owners and ticker/config jobs after every assertion path. */
    @After
    fun close() {
        owner.clear()
        Dispatchers.resetMain()
    }

    /**
     * Supply real account workflows and an explicit independently controlled configuration port.
     */
    private fun viewModel(): AuthViewModel {
        val refresh =
            configurationLoader(
                cached = {
                    cachedReadGate?.await()
                    cachedConfiguration
                },
            ) {
                refreshes++
                gate?.await()
                if (unavailable) {
                    throw RemoteConfigFailure(RemoteConfigIssue.Unavailable)
                }
                configuration
            }
        return AuthViewModel(
                SavedStateHandle(),
                ServerAccountAccess(
                    api,
                    MemoryAuthenticationSessionStore(),
                    clock,
                    RecordingWalletRepository(),
                ),
                clock,
                refresh,
            )
            .also { owner.put("auth", it) }
    }

    /** Saved disabled switches survive cold start, an outage and a new presentation owner. */
    @Test
    fun coldOfflineLaunchRestoresSavedPolicyInsteadOfEmailDefaults() {
        cachedConfiguration = configuration.copy(login = AuthenticationMethods(false, false))
        unavailable = true
        gate = CompletableDeferred()
        val first = viewModel()
        first.accept(AuthIntent.SignIn)
        assertEquals(cachedConfiguration, first.uiState.value.configuration)
        assertTrue(first.uiState.value.availableContacts.isEmpty())
        assertTrue(first.uiState.value.isConfigRefreshing)
        gate!!.complete(Unit)
        assertTrue(first.uiState.value.isConfigUnavailable)
        assertTrue(first.uiState.value.availableContacts.isEmpty())
        val recreated = viewModel()
        recreated.accept(AuthIntent.SignIn)
        assertEquals(cachedConfiguration, recreated.uiState.value.configuration)
        assertTrue(recreated.uiState.value.availableContacts.isEmpty())
        recreated.accept(AuthIntent.Continue)
        assertEquals(0, api.codeRequests)
        assertEquals(0, api.passwordRequests)
    }

    /** Online recovery replaces saved switches without submitting login or signup. */
    @Test
    fun onlineRefreshReplacesCachedPolicyWithoutReplayingAccountOperations() {
        cachedConfiguration = configuration.copy(login = AuthenticationMethods(false, false))
        configuration =
            configuration.copy(version = "v2", login = AuthenticationMethods(false, true))
        gate = CompletableDeferred()
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        assertTrue(vm.uiState.value.availableContacts.isEmpty())
        gate!!.complete(Unit)
        assertEquals(configuration, vm.uiState.value.configuration)
        assertEquals(listOf(AuthContactType.Phone), vm.uiState.value.availableContacts)
        assertEquals(AuthContactType.Phone, vm.uiState.value.contactType)
        assertFalse(vm.uiState.value.isConfigRefreshing)
        assertFalse(vm.uiState.value.isConfigUnavailable)
        assertEquals(0, api.codeRequests)
        assertEquals(0, api.passwordRequests)
        assertEquals(0, api.verifyRequests)
    }

    /** Late disk reads update choices without replacing a newer draft or route. */
    @Test
    fun delayedCacheRestorationPreservesTheCurrentDraftAndRequiresExplicitRecovery() {
        cachedReadGate = CompletableDeferred()
        gate = CompletableDeferred()
        cachedConfiguration = configuration.copy(login = AuthenticationMethods(false, true))
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("ada@example.test"))
        cachedReadGate!!.complete(Unit)
        assertEquals(AuthScreen.SignIn, vm.uiState.value.screen)
        assertEquals("ada@example.test", vm.uiState.value.contact)
        assertEquals(AuthContactType.Email, vm.uiState.value.contactType)
        assertEquals(listOf(AuthContactType.Phone), vm.uiState.value.availableContacts)
        vm.accept(AuthIntent.Continue)
        assertEquals(0, api.codeRequests)
        unavailable = true
        gate!!.complete(Unit)
        assertEquals("ada@example.test", vm.uiState.value.contact)
        assertTrue(vm.uiState.value.isConfigUnavailable)
        assertEquals(0, api.codeRequests)
    }

    /** Signup and login choose their own enabled contact without allowing disabled user intents. */
    @Test
    fun switchesAreIndependentForEachOperation() {
        configuration =
            configuration.copy(
                signup = AuthenticationMethods(true, false),
                login = AuthenticationMethods(false, true),
            )
        val vm = viewModel()
        vm.accept(AuthIntent.CreateAccount)
        assertEquals(listOf(AuthContactType.Email), vm.uiState.value.availableContacts)
        assertEquals(AuthContactType.Email, vm.uiState.value.contactType)
        vm.accept(AuthIntent.SelectContactType(AuthContactType.Phone))
        assertEquals(AuthContactType.Email, vm.uiState.value.contactType)
        vm.accept(AuthIntent.SignIn)
        assertEquals(listOf(AuthContactType.Phone), vm.uiState.value.availableContacts)
    }

    /** First-load failure offers only email, with both signup/login credential methods usable. */
    @Test
    fun firstLoadFailureDefaultsToEmailAndPermitsPasswordOrCode() {
        unavailable = true
        val vm = viewModel()
        for (creating in listOf(false, true)) {
            for (method in AuthMethod.entries) {
                vm.accept(if (creating) AuthIntent.CreateAccount else AuthIntent.SignIn)
                assertTrue(vm.uiState.value.isConfigUnavailable)
                assertEquals(listOf(AuthContactType.Email), vm.uiState.value.availableContacts)
                assertEquals(AuthContactType.Email, vm.uiState.value.contactType)
                vm.accept(AuthIntent.SelectContactType(AuthContactType.Phone))
                assertEquals(AuthContactType.Email, vm.uiState.value.contactType)
                vm.accept(AuthIntent.SelectMethod(method))
                vm.accept(AuthIntent.EditContact("ada@example.test"))
                vm.accept(AuthIntent.EditPassword("a sufficiently long fallback password"))
                vm.accept(AuthIntent.Continue)
                assertNull(vm.uiState.value.error)
                assertEquals(AuthContactType.Email, api.contact?.kind)
                assertEquals(
                    if (!creating && method == AuthMethod.Password) AuthScreen.Dashboard
                    else AuthScreen.Verification,
                    vm.uiState.value.screen,
                )
            }
        }
        assertEquals(3, api.codeRequests)
        assertEquals(1, api.passwordRequests)
        assertNull(vm.uiState.value.configuration)
    }

    /** Pending configuration never hides email entry or replays the submitted code request. */
    @Test
    fun initialLoadingUsesEmailWithoutWaitingForRemoteConfig() {
        gate = CompletableDeferred()
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        assertTrue(vm.uiState.value.isConfigRefreshing)
        assertEquals(listOf(AuthContactType.Email), vm.uiState.value.availableContacts)
        vm.accept(AuthIntent.EditContact("ada@example.test"))
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.Verification, vm.uiState.value.screen)
        val challenge = vm.uiState.value.challenge
        gate!!.complete(Unit)
        assertEquals(challenge, vm.uiState.value.challenge)
        assertEquals(1, api.codeRequests)
        assertEquals(0, api.verifyRequests)
    }

    /**
     * A published both-disabled policy remains authoritative for the UI even if a refresh fails.
     */
    @Test
    fun failureNeverOverridesKnownDisabledSwitchesWithEmailDefaults() {
        configuration = configuration.copy(login = AuthenticationMethods(false, false))
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        unavailable = true
        vm.accept(AuthIntent.RefreshSession)
        assertTrue(vm.uiState.value.isConfigUnavailable)
        assertTrue(vm.uiState.value.availableContacts.isEmpty())
        vm.accept(AuthIntent.EditContact("ada@example.test"))
        vm.accept(AuthIntent.Continue)
        assertEquals(0, api.codeRequests)
        unavailable = false
        configuration = configuration.copy(login = AuthenticationMethods(true, false))
        vm.accept(AuthIntent.RetryConfiguration)
        assertEquals(listOf(AuthContactType.Email), vm.uiState.value.availableContacts)
        assertEquals(0, api.codeRequests)
    }

    /**
     * Background refresh failures preserve the last fetched choices and draft without submitting.
     */
    @Test
    fun foregroundFailureRetainsKnownPolicyAndDraft() {
        configuration = configuration.copy(login = AuthenticationMethods(false, true))
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        unavailable = true
        vm.accept(AuthIntent.RefreshSession)
        assertEquals(listOf(AuthContactType.Phone), vm.uiState.value.availableContacts)
        assertEquals("08012345678", vm.uiState.value.contact)
        assertEquals(0, api.codeRequests)
    }

    /** A late policy read changes availability, never the newer navigation or credentials. */
    @Test
    fun lateConfigurationDoesNotRestoreAnOldRouteOrSubmit() = runTest {
        val vm = viewModel()
        gate = CompletableDeferred()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.CreateAccount)
        vm.accept(AuthIntent.Back)
        gate!!.complete(Unit)
        assertEquals(AuthScreen.Welcome, vm.uiState.value.screen)
        assertEquals(0, api.codeRequests)
        assertEquals(2, refreshes)
    }

    /**
     * Disabling a contact during code entry blocks verification and resend without consuming proof.
     */
    @Test
    fun policyChangesDuringVerificationNeverResubmitOrRedeemAutomatically() {
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.Verification, vm.uiState.value.screen)
        val challenge = vm.uiState.value.challenge
        vm.accept(AuthIntent.EditCode("001234"))
        configuration = configuration.copy(login = AuthenticationMethods(true, false))
        vm.accept(AuthIntent.RefreshSession)
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.ResendCode)
        assertEquals(0, api.verifyRequests)
        assertEquals(1, api.codeRequests)
        assertEquals(challenge, vm.uiState.value.challenge)
        assertEquals("001234", vm.uiState.value.code)
        configuration = configuration.copy(login = AuthenticationMethods(true, true))
        vm.accept(AuthIntent.RetryConfiguration)
        assertEquals(0, api.verifyRequests)
        assertTrue(vm.uiState.value.isContactEnabled(AuthContactType.Phone))
    }

    /**
     * A disabled-method 403 refreshes choices once and preserves entered contact for explicit
     * recovery.
     */
    @Test
    fun disabledMethodRejectionRefreshesWithoutRetryingAuthentication() {
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        configuration = configuration.copy(login = AuthenticationMethods(true, false))
        api.failure = AuthenticationFailure(AccountAccessIssue.MethodDisabled)
        vm.accept(AuthIntent.Continue)
        assertEquals(1, api.codeRequests)
        assertEquals(AuthError.MethodDisabled, vm.uiState.value.error)
        assertEquals("08012345678", vm.uiState.value.contact)
        assertEquals(listOf(AuthContactType.Email), vm.uiState.value.availableContacts)
        vm.accept(AuthIntent.Continue)
        assertEquals(1, api.codeRequests)
        vm.accept(AuthIntent.SelectContactType(AuthContactType.Email))
        assertEquals("", vm.uiState.value.contact)
    }

    /**
     * Each operation selects its enabled credential and ignores attempts to choose a disabled one.
     */
    @Test
    fun credentialSwitchesAreIndependentForSignupAndLogin() {
        configuration =
            configuration.copy(
                signup =
                    AuthenticationMethods(true, true, passwordEnabled = false, otpEnabled = true),
                login =
                    AuthenticationMethods(true, true, passwordEnabled = true, otpEnabled = false),
            )
        val vm = viewModel()
        vm.accept(AuthIntent.CreateAccount)
        assertEquals(listOf(AuthMethod.OneTimeCode), vm.uiState.value.availableMethods)
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        assertEquals(AuthMethod.OneTimeCode, vm.uiState.value.method)
        vm.accept(AuthIntent.SignIn)
        assertEquals(listOf(AuthMethod.Password), vm.uiState.value.availableMethods)
        assertEquals(AuthMethod.Password, vm.uiState.value.method)
        vm.accept(AuthIntent.SelectMethod(AuthMethod.OneTimeCode))
        assertEquals(AuthMethod.Password, vm.uiState.value.method)
        assertEquals(0, api.codeRequests)
        assertEquals(0, api.passwordRequests)
    }

    /** Contacts alone cannot make an operation usable when both credential choices are disabled. */
    @Test
    fun bothCredentialMethodsDisabledPreventAllAuthenticationRequests() {
        val disabled = AuthenticationMethods(true, true, false, false)
        configuration = configuration.copy(signup = disabled, login = disabled)
        val vm = viewModel()
        for (creating in listOf(false, true)) {
            vm.accept(if (creating) AuthIntent.CreateAccount else AuthIntent.SignIn)
            assertFalse(vm.uiState.value.hasAvailableAuthentication)
            assertTrue(vm.uiState.value.availableContacts.isNotEmpty())
            assertTrue(vm.uiState.value.availableMethods.isEmpty())
            vm.accept(AuthIntent.EditContact("08012345678"))
            vm.accept(AuthIntent.Continue)
        }
        assertEquals(0, api.codeRequests)
        assertEquals(0, api.passwordRequests)
    }

    /** Disabling a drafted password requires an explicit switch and never exposes or submits it. */
    @Test
    fun disablingDraftedPasswordPreservesContactUntilExplicitMethodRecovery() {
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.EditPassword("a sufficiently long private password"))
        configuration = configuration.copy(login = AuthenticationMethods(true, true, false, true))
        vm.accept(AuthIntent.RetryConfiguration)
        assertEquals(AuthMethod.Password, vm.uiState.value.method)
        assertTrue(vm.uiState.value.password.isNotEmpty())
        assertFalse(vm.uiState.value.isSelectedAuthenticationEnabled)
        vm.accept(AuthIntent.Continue)
        assertEquals(0, api.passwordRequests)
        vm.accept(AuthIntent.SelectMethod(AuthMethod.OneTimeCode))
        assertEquals("", vm.uiState.value.password)
        assertEquals("08012345678", vm.uiState.value.contact)
        assertEquals(0, api.codeRequests)
        vm.accept(AuthIntent.Continue)
        assertEquals(1, api.codeRequests)
    }

    /** A disabled OTP challenge keeps its proof and code but cannot be resent or consumed. */
    @Test
    fun disablingOtpDuringVerificationRequiresExplicitRecoveryWithoutReplay() {
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.Continue)
        val challenge = vm.uiState.value.challenge
        vm.accept(AuthIntent.EditCode("001234"))
        configuration = configuration.copy(login = AuthenticationMethods(true, true, true, false))
        vm.accept(AuthIntent.RetryConfiguration)
        assertEquals(AuthMethod.OneTimeCode, vm.uiState.value.method)
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.ResendCode)
        assertEquals(challenge, vm.uiState.value.challenge)
        assertEquals("001234", vm.uiState.value.code)
        assertEquals(0, api.verifyRequests)
        assertEquals(1, api.codeRequests)
        configuration = configuration.copy(login = AuthenticationMethods(true, true, true, true))
        vm.accept(AuthIntent.RetryConfiguration)
        assertTrue(vm.uiState.value.isSelectedAuthenticationEnabled)
        assertEquals(0, api.verifyRequests)
        vm.accept(AuthIntent.Continue)
        assertEquals(1, api.verifyRequests)
    }

    /**
     * Mandatory contact verification remains available for password signup with passwordless OTP
     * off.
     */
    @Test
    fun passwordSignupStillVerifiesItsContactWhenPasswordlessOtpIsDisabled() {
        configuration = configuration.copy(signup = AuthenticationMethods(true, true, true, false))
        val vm = viewModel()
        vm.accept(AuthIntent.CreateAccount)
        assertEquals(AuthMethod.Password, vm.uiState.value.method)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.EditPassword("a sufficiently long private password"))
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.Verification, vm.uiState.value.screen)
        assertTrue(vm.uiState.value.challenge!!.usesPassword)
        assertTrue(vm.uiState.value.isSelectedAuthenticationEnabled)
        vm.accept(AuthIntent.EditCode("001234"))
        vm.accept(AuthIntent.Continue)
        assertEquals(1, api.verifyRequests)
        assertEquals(AuthScreen.Dashboard, vm.uiState.value.screen)
    }

    /**
     * Refresh cannot change the captured credential identity while a password request is suspended.
     */
    @Test
    fun credentialPolicyChangeDuringSubmissionKeepsTheCapturedMethod() {
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.EditPassword("a sufficiently long private password"))
        api.gate = CompletableDeferred()
        vm.accept(AuthIntent.Continue)
        assertTrue(vm.uiState.value.isProcessing)
        configuration = configuration.copy(login = AuthenticationMethods(true, true, false, true))
        vm.accept(AuthIntent.RetryConfiguration)
        assertEquals(AuthMethod.Password, vm.uiState.value.method)
        api.gate!!.complete(Unit)
        assertEquals(1, api.passwordRequests)
        assertEquals(0, api.codeRequests)
        assertEquals(AuthScreen.Dashboard, vm.uiState.value.screen)
    }

    /**
     * Safe automatic choice changes cannot retain an obsolete password error or reveal preference.
     */
    @Test
    fun automaticCredentialSelectionClearsObsoleteErrorsAndVisibility() {
        val vm = viewModel()
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.TogglePasswordVisibility)
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthError.Password, vm.uiState.value.error)
        assertTrue(vm.uiState.value.isPasswordVisible)
        configuration = configuration.copy(login = AuthenticationMethods(true, true, false, true))
        vm.accept(AuthIntent.RetryConfiguration)
        assertEquals(AuthMethod.OneTimeCode, vm.uiState.value.method)
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.failureCause)
        assertFalse(vm.uiState.value.isPasswordVisible)
        assertEquals("08012345678", vm.uiState.value.contact)
        assertEquals(0, api.codeRequests)
        assertEquals(0, api.passwordRequests)
    }
}
