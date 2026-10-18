package com.kesofty.axiapay.presentation.auth

import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.application.auth.AccountAccess
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.domain.auth.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns immutable account-entry state and explicit application intents. Credentials and challenges
 * remain transient; only payment-preview navigation is saved. Session storage and server calls
 * belong to the injected application workflow, independent of the payment ViewModel/repository.
 */
@HiltViewModel
class AuthViewModel
@Inject
constructor(
    private val handle: SavedStateHandle,
    private val access: AccountAccess,
    private val clock: Clock,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            freshState(
                screen =
                    if (handle.get<Boolean>(DASHBOARD_ENTERED) == true) AuthScreen.Dashboard
                    else AuthScreen.Welcome,
            ),
        )
    val uiState = mutableState.asStateFlow()
    private var pendingAction: Job? = null
    private var ticker: Job? = null
    private var visit = 0L
    private var retryUntil: Instant? = null

    init {
        if (access.mode == AccountAccessMode.Server) {
            restoreSession()
        }
    }

    /** Admits one operation per screen visit and routes all form edits through this reducer. */
    @MainThread
    fun accept(intent: AuthIntent) {
        when (intent) {
            AuthIntent.SignIn -> navigate(freshState(AuthScreen.SignIn))
            AuthIntent.CreateAccount ->
                navigate(freshState(AuthScreen.CreateAccount).copy(isCreatingAccount = true))
            AuthIntent.Back -> goBack()
            AuthIntent.ExploreDemo -> {
                handle[DASHBOARD_ENTERED] = true
                navigate(
                    freshState(AuthScreen.Dashboard)
                        .copy(
                            account = mutableState.value.account,
                            wallet = mutableState.value.wallet,
                        ),
                )
            }
            AuthIntent.OpenDashboard -> {
                val state = mutableState.value
                if (state.isDemo || state.account == null || state.isProcessing) {
                    return
                }
                handle[DASHBOARD_ENTERED] = true
                navigate(
                    freshState(AuthScreen.Dashboard)
                        .copy(account = state.account, wallet = state.wallet),
                )
            }
            AuthIntent.OpenAccount -> {
                handle[DASHBOARD_ENTERED] = false
                val account = mutableState.value.account
                if (!mutableState.value.isDemo && account == null) {
                    restoreSession(AuthScreen.Complete)
                    return
                }
                navigate(
                    freshState(if (account == null) AuthScreen.Welcome else AuthScreen.Complete)
                        .copy(account = account, wallet = mutableState.value.wallet),
                )
            }
            AuthIntent.Continue -> submit()
            AuthIntent.ResendCode -> resendCode()
            AuthIntent.RetrySession -> restoreSession()
            AuthIntent.RetryWallet -> {
                if (
                    mutableState.value.screen !in
                        setOf(AuthScreen.Complete, AuthScreen.Dashboard) ||
                        mutableState.value.account == null ||
                        mutableState.value.isDemo
                ) {
                    return
                }
                execute(
                    restoring = true,
                    walletRefresh = true,
                    restoredDestination = mutableState.value.screen,
                ) {
                    access.restore()
                }
            }
            AuthIntent.RefreshSession -> refreshIfIdle()
            AuthIntent.SignOut -> signOut()
            is AuthIntent.SelectContactType -> selectContactType(intent.contactType)
            is AuthIntent.SelectMethod -> selectMethod(intent.method)
            is AuthIntent.EditContact ->
                editForm {
                    copy(
                        contact =
                            intent.value.take(
                                if (contactType == AuthContactType.Phone) 32 else 254,
                            ),
                        error = null,
                        failureCause = null,
                    )
                }
            is AuthIntent.EditName ->
                editForm { copy(name = intent.value.take(80), error = null, failureCause = null) }
            is AuthIntent.EditPassword -> editPassword(intent.value)
            AuthIntent.TogglePasswordVisibility ->
                editForm {
                    copy(isPasswordVisible = method == AuthMethod.Password && !isPasswordVisible)
                }
            is AuthIntent.EditCode -> editCode(intent.value)
        }
    }

    /**
     * Creates route state with capabilities from DI while excluding credentials and challenge IDs.
     */
    private fun freshState(screen: AuthScreen = AuthScreen.Welcome) =
        AuthUiState(screen = screen, mode = access.mode)

    /**
     * Keeps form inputs stable during a request and excludes edits from verification/account
     * routes.
     */
    private fun editForm(update: AuthUiState.() -> AuthUiState) {
        val state = mutableState.value
        if (
            state.isProcessing ||
                state.screen !in setOf(AuthScreen.SignIn, AuthScreen.CreateAccount)
        ) {
            return
        }
        mutableState.value = state.update()
    }

    /** Drops credentials belonging to the previous contact format without making a request. */
    private fun selectContactType(contactType: AuthContactType) {
        if (contactType == mutableState.value.contactType) {
            return
        }
        editForm {
            copy(
                contactType = contactType,
                contact = "",
                password = "",
                isPasswordVisible = false,
                code = "",
                error = null,
                failureCause = null,
                isCodeResent = false,
            )
        }
    }

    /** Clears hidden credentials when changing methods without submitting either choice. */
    private fun selectMethod(method: AuthMethod) {
        if (method == mutableState.value.method) {
            return
        }
        editForm {
            copy(
                method = method,
                password = "",
                isPasswordVisible = false,
                code = "",
                error = null,
                failureCause = null,
                isCodeResent = false,
            )
        }
    }

    /** Preserves exact password characters and accepts edits only on a password form. */
    private fun editPassword(password: String) {
        if (mutableState.value.method != AuthMethod.Password) {
            return
        }
        editForm { copy(password = password, error = null, failureCause = null) }
    }

    /** Keeps code leading zeros and never submits from a text change or lifecycle callback. */
    private fun editCode(code: String) {
        val state = mutableState.value
        if (state.isProcessing || state.screen != AuthScreen.Verification) {
            return
        }
        mutableState.value =
            state.copy(
                code = code.filter { it in '0'..'9' }.take(6),
                error = null,
                failureCause = null,
            )
    }

    /** Clears abandoned proof and returns real account details to the authenticated dashboard. */
    private fun goBack() {
        val state = mutableState.value
        val previous =
            when (state.screen) {
                AuthScreen.Verification ->
                    state.copy(
                        screen =
                            if (state.isCreatingAccount) AuthScreen.CreateAccount
                            else AuthScreen.SignIn,
                        challenge = null,
                        code = "",
                        password = "",
                        isPasswordVisible = false,
                        error = null,
                        failureCause = null,
                        isCodeResent = false,
                        codeSecondsRemaining = 0,
                        resendSecondsRemaining = 0,
                    )
                AuthScreen.Complete ->
                    if (state.isDemo) freshState()
                    else
                        freshState(AuthScreen.Dashboard)
                            .copy(account = state.account, wallet = state.wallet)
                else -> freshState()
            }
        navigate(previous)
    }

    /** Navigation owns cancellation and prevents a late result from replacing a newer route. */
    private fun navigate(state: AuthUiState) {
        visit += 1
        pendingAction?.cancel()
        ticker?.cancel()
        retryUntil = null
        mutableState.value = state.copy(isProcessing = false, retryAfterSeconds = 0)
    }

    /**
     * Captures only the current form; application operations own validation and endpoint choice.
     */
    private fun capturedInput(state: AuthUiState) =
        AccountAccessInput(
            state.contactType,
            state.contact,
            state.method,
            state.password,
            state.name,
            state.isCreatingAccount,
        )

    /** Explicitly submits an eligible form or proof, with no duplicate work during cooldowns. */
    private fun submit() {
        val state = mutableState.value
        if (state.retryAfterSeconds > 0) {
            return
        }
        when (state.screen) {
            AuthScreen.Verification -> execute { access.verify(state.challenge, state.code) }
            AuthScreen.SignIn,
            AuthScreen.CreateAccount -> execute { access.begin(capturedInput(state)) }
            else -> Unit
        }
    }

    /** Discards an old code immediately; server cooldowns and purpose remain application-owned. */
    private fun resendCode() {
        val state = mutableState.value
        if (
            state.screen != AuthScreen.Verification ||
                state.resendSecondsRemaining > 0 ||
                state.retryAfterSeconds > 0
        ) {
            return
        }
        execute(isReset = true) { access.resend(state.challenge, capturedInput(state)) }
    }

    /** Revalidates only stable account routes; foregrounding preserves drafts and code entry. */
    private fun refreshIfIdle() {
        val state = mutableState.value
        if (state.isDemo || state.isProcessing || state.account == null) {
            return
        }
        if (state.screen !in setOf(AuthScreen.Complete, AuthScreen.Dashboard)) {
            return
        }
        restoreSession()
    }

    /**
     * Revalidates protected access, opening Home unless account details are explicitly selected.
     */
    private fun restoreSession(requestedDestination: AuthScreen? = null) {
        if (mutableState.value.isDemo || mutableState.value.isProcessing) {
            return
        }
        val destination =
            requestedDestination
                ?: if (mutableState.value.screen == AuthScreen.Complete) AuthScreen.Complete
                else AuthScreen.Dashboard
        navigate(freshState(AuthScreen.Restoring))
        execute(restoring = true, restoredDestination = destination) { access.restore() }
    }

    /** Keeps revocation failure retryable and never clears the unrelated payment preview owner. */
    private fun signOut() {
        val state = mutableState.value
        if (
            state.isDemo || state.screen !in setOf(AuthScreen.Complete, AuthScreen.SessionRecovery)
        ) {
            return
        }
        execute { access.signOut() }
    }

    /**
     * Runs one owned request; cancellation propagates and safe failures remain explicit UI state.
     */
    private fun execute(
        isReset: Boolean = false,
        restoring: Boolean = false,
        walletRefresh: Boolean = false,
        restoredDestination: AuthScreen = AuthScreen.Complete,
        operation: suspend () -> AccountAccessResult,
    ) {
        val submitted = mutableState.value
        if (submitted.isProcessing) {
            return
        }
        val origin = visit
        mutableState.value =
            submitted.copy(
                isProcessing = true,
                isWalletRefreshing = walletRefresh,
                error = null,
                failureCause = null,
                code = if (isReset) "" else submitted.code,
            )
        pendingAction = viewModelScope.launch {
            try {
                val result = operation()
                if (visit != origin) {
                    return@launch
                }
                applyResult(result, submitted, isReset, restoring, restoredDestination)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (visit != origin) {
                    return@launch
                }
                val safe = failure as? AuthenticationFailure
                val issue = safe?.issue ?: AccountAccessIssue.Unavailable
                showFailure(
                    issue,
                    safe?.retryAfterSeconds ?: 0,
                    safe?.requestReference,
                    failure,
                    restoring,
                )
            }
        }
    }

    /** Reduces success without storing a bearer token or invoking any payment operation. */
    private fun applyResult(
        result: AccountAccessResult,
        submitted: AuthUiState,
        isReset: Boolean,
        restoring: Boolean,
        restoredDestination: AuthScreen,
    ) {
        when (result) {
            is AccountAccessResult.CodeRequired -> {
                mutableState.value =
                    mutableState.value.copy(
                        screen = AuthScreen.Verification,
                        challenge = result.challenge,
                        isProcessing = false,
                        error = null,
                        isCodeResent = isReset,
                        password = "",
                        isPasswordVisible = false,
                        code = "",
                    )
                startTicker()
            }
            is AccountAccessResult.SignedIn -> {
                mutableState.value =
                    freshState(if (restoring) restoredDestination else AuthScreen.Dashboard)
                        .copy(account = result.account, wallet = result.wallet)
                ticker?.cancel()
            }
            AccountAccessResult.DemoComplete ->
                mutableState.value =
                    freshState(AuthScreen.Complete)
                        .copy(isCreatingAccount = submitted.isCreatingAccount)
            AccountAccessResult.SignedOut -> {
                val preview = restoring && handle.get<Boolean>(DASHBOARD_ENTERED) == true
                if (!preview) {
                    handle[DASHBOARD_ENTERED] = false
                }
                mutableState.value =
                    freshState(if (preview) AuthScreen.Dashboard else AuthScreen.Welcome)
            }
            is AccountAccessResult.Rejected ->
                showFailure(
                    result.issue,
                    result.retryAfterSeconds,
                    result.requestReference,
                    restoring = restoring,
                )
        }
    }

    /**
     * Maps only allowlisted failures; connectivity and storage errors never enable account access.
     */
    private fun showFailure(
        issue: AccountAccessIssue,
        retryAfterSeconds: Long,
        reference: String?,
        cause: Exception? = null,
        restoring: Boolean = false,
    ) {
        val error =
            when (issue) {
                AccountAccessIssue.Phone -> AuthError.Phone
                AccountAccessIssue.Email -> AuthError.Email
                AccountAccessIssue.Name -> AuthError.Name
                AccountAccessIssue.Password -> AuthError.Password
                AccountAccessIssue.Code -> AuthError.Code
                AccountAccessIssue.InvalidCredentials -> AuthError.Credentials
                AccountAccessIssue.CodeRejected -> AuthError.CodeRejected
                AccountAccessIssue.CodeExpired -> AuthError.CodeExpired
                AccountAccessIssue.RateLimited -> AuthError.RateLimited
                AccountAccessIssue.Network -> AuthError.Network
                AccountAccessIssue.Configuration -> AuthError.Configuration
                AccountAccessIssue.SecureStorage -> AuthError.SecureStorage
                AccountAccessIssue.InvalidRequest -> AuthError.InvalidRequest
                AccountAccessIssue.SignupPasswordRequired -> AuthError.PasswordAgain
                AccountAccessIssue.Unavailable,
                AccountAccessIssue.InvalidResponse -> AuthError.Unavailable
            }
        if (issue == AccountAccessIssue.SignupPasswordRequired) {
            val previous =
                mutableState.value.copy(
                    screen = AuthScreen.CreateAccount,
                    challenge = null,
                    password = "",
                    code = "",
                    error = error,
                    isCodeResent = false,
                    codeSecondsRemaining = 0,
                    resendSecondsRemaining = 0,
                )
            navigate(previous)
            return
        }
        retryUntil = clock.instant().plusSeconds(retryAfterSeconds.coerceAtLeast(0))
        mutableState.value =
            mutableState.value.copy(
                screen = if (restoring) AuthScreen.SessionRecovery else mutableState.value.screen,
                isProcessing = false,
                isWalletRefreshing = false,
                error = error,
                failureCause = cause,
                requestReference = reference,
                retryAfterSeconds = retryAfterSeconds,
            )
        startTicker()
    }

    /** Updates presentation countdowns only; timing never submits or confirms an operation. */
    private fun startTicker() {
        ticker?.cancel()
        updateTiming()
        if (mutableState.value.isDemo) {
            return
        }
        ticker = viewModelScope.launch {
            while (hasCountdown()) {
                delay(1000)
                updateTiming()
            }
        }
    }

    /** Limits timer lifetime to visible code/cooldown state rather than a global background job. */
    private fun hasCountdown(): Boolean {
        val state = mutableState.value
        return state.retryAfterSeconds > 0 ||
            (state.screen == AuthScreen.Verification &&
                (state.codeSecondsRemaining > 0 || state.resendSecondsRemaining > 0))
    }

    /** Derives readable timing from absolute in-memory deadlines, including after recreation. */
    private fun updateTiming() {
        val state = mutableState.value
        val retrySeconds = secondsUntil(retryUntil)
        mutableState.value =
            state.copy(
                codeSecondsRemaining = secondsUntil(state.challenge?.expiresAt),
                resendSecondsRemaining = secondsUntil(state.challenge?.resendAt),
                retryAfterSeconds = retrySeconds,
                error =
                    if (state.error == AuthError.RateLimited && retrySeconds == 0L) null
                    else state.error,
            )
    }

    /** Rounds up the last partial second so a control does not enable before its local deadline. */
    private fun secondsUntil(deadline: Instant?): Long {
        if (deadline == null) {
            return 0
        }
        return ((Duration.between(clock.instant(), deadline).toMillis() + 999) / 1000)
            .coerceAtLeast(0)
    }

    /** A payment-preview route flag is never an authentication credential or server permission. */
    private companion object {
        const val DASHBOARD_ENTERED = "auth.demoEntered"
    }
}
