package com.kesofty.axiapay.presentation.auth

import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.application.CompleteDemoAccess
import com.kesofty.axiapay.application.PrepareDemoAccess
import com.kesofty.axiapay.domain.DemoAccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns auth preview state and cancels work on navigation. Only the dashboard-entry flag is saved;
 * recreation retains this ViewModel, while process restoration requires form details again. It
 * never owns, resets, authenticates, or authorizes the separate payment session.
 */
class AuthViewModel(
    private val handle: SavedStateHandle,
    private val prepareAccess: PrepareDemoAccess,
    private val completeAccess: CompleteDemoAccess,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            AuthUiState(
                screen =
                    if (handle.get<Boolean>(DEMO_ENTERED) == true) AuthScreen.Dashboard
                    else AuthScreen.Welcome,
            ),
        )
    val uiState = mutableState.asStateFlow()
    private var pendingAction: Job? = null
    private var visit = 0L

    /** Reduces user input on the main thread, admitting at most one preview operation at a time. */
    @MainThread
    fun accept(intent: AuthIntent) {
        when (intent) {
            AuthIntent.SignIn -> navigate(AuthUiState(screen = AuthScreen.SignIn))
            AuthIntent.CreateAccount ->
                navigate(AuthUiState(screen = AuthScreen.CreateAccount, isCreatingAccount = true))
            AuthIntent.Back -> goBack()
            AuthIntent.ExploreDemo -> {
                handle[DEMO_ENTERED] = true
                navigate(AuthUiState(screen = AuthScreen.Dashboard))
            }
            AuthIntent.PreviewAuth -> {
                handle[DEMO_ENTERED] = false
                navigate(AuthUiState())
            }
            AuthIntent.Continue -> submit()
            AuthIntent.ResetCodePreview -> {
                if (mutableState.value.screen != AuthScreen.Verification) return
                execute(isReset = true)
            }
            is AuthIntent.EditPhone ->
                edit {
                    copy(phoneNumber = intent.value.take(20), error = null, failureCause = null)
                }
            is AuthIntent.EditName ->
                edit { copy(name = intent.value.take(80), error = null, failureCause = null) }
            is AuthIntent.EditCode ->
                edit {
                    copy(
                        code = intent.value.filter { it in '0'..'9' }.take(6),
                        error = null,
                        failureCause = null,
                    )
                }
        }
    }

    /** Keeps fields stable while a submitted operation owns their values. */
    private fun edit(update: AuthUiState.() -> AuthUiState) {
        if (mutableState.value.isProcessing) return
        mutableState.value = mutableState.value.update()
    }

    /**
     * Back navigation never retains a verification code or allows late work to take over the
     * screen.
     */
    private fun goBack() {
        val state = mutableState.value
        val previous =
            when (state.screen) {
                AuthScreen.Verification ->
                    state.copy(
                        screen =
                            if (state.isCreatingAccount) AuthScreen.CreateAccount
                            else AuthScreen.SignIn,
                        code = "",
                        error = null,
                        failureCause = null,
                        previewReset = false,
                    )
                else -> AuthUiState()
            }
        navigate(previous)
    }

    /** Owns route changes and invalidates asynchronous results from earlier screen visits. */
    private fun navigate(state: AuthUiState) {
        visit += 1
        pendingAction?.cancel()
        mutableState.value = state.copy(isProcessing = false)
    }

    /** Dispatches only actions valid for the currently rendered form. */
    private fun submit() {
        when (mutableState.value.screen) {
            AuthScreen.SignIn,
            AuthScreen.CreateAccount,
            AuthScreen.Verification -> execute()
            else -> Unit
        }
    }

    /**
     * Maps application results to the originating screen; cancellation is never a visible failure.
     */
    private fun execute(isReset: Boolean = false) {
        val state = mutableState.value
        if (state.isProcessing) return
        val origin = visit
        mutableState.value =
            state.copy(
                isProcessing = true,
                error = null,
                failureCause = null,
                code = if (isReset) "" else state.code,
            )
        pendingAction = viewModelScope.launch {
            try {
                val result =
                    when {
                        state.screen == AuthScreen.Verification && !isReset ->
                            completeAccess(state.code)
                        else ->
                            prepareAccess(state.phoneNumber, state.name, state.isCreatingAccount)
                    }
                if (visit != origin) return@launch
                applyResult(result, state, isReset)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (visit != origin) return@launch
                mutableState.value =
                    mutableState.value.copy(
                        isProcessing = false,
                        error = AuthError.Unavailable,
                        failureCause = failure,
                    )
            }
        }
    }

    /** Replaces form state with an explicit next route; no navigation effects can be replayed. */
    private fun applyResult(result: DemoAccessResult, submitted: AuthUiState, isReset: Boolean) {
        val nextScreen =
            when {
                result != DemoAccessResult.PreviewReady -> submitted.screen
                submitted.screen == AuthScreen.Verification && !isReset -> AuthScreen.Complete
                else -> AuthScreen.Verification
            }
        val error =
            when (result) {
                DemoAccessResult.InvalidPhone -> AuthError.Phone
                DemoAccessResult.InvalidName -> AuthError.Name
                DemoAccessResult.InvalidCode -> AuthError.Code
                DemoAccessResult.PreviewReady -> null
            }
        if (nextScreen == AuthScreen.Complete) {
            mutableState.value =
                AuthUiState(screen = nextScreen, isCreatingAccount = submitted.isCreatingAccount)
            return
        }
        mutableState.value =
            mutableState.value.copy(
                screen = nextScreen,
                isProcessing = false,
                error = error,
                previewReset = isReset,
            )
    }

    /** Saved flag controls demo routing only and cannot be used as an authorization credential. */
    private companion object {
        const val DEMO_ENTERED = "auth.demoEntered"
    }
}
