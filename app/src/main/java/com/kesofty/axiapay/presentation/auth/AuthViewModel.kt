package com.kesofty.axiapay.presentation.auth

import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
@HiltViewModel
class AuthViewModel
@Inject
constructor(
    private val handle: SavedStateHandle,
    private val prepareAccess: PrepareDemoAccess,
    private val completeAccess: CompleteDemoAccess,
    private val resendAccessCode: ResendDemoAccessCode,
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
            is AuthIntent.SelectContactType -> selectContactType(intent.contactType)
            is AuthIntent.SelectMethod -> selectMethod(intent.method)
            is AuthIntent.EditContact ->
                editForm {
                    copy(
                        contact =
                            intent.value.take(
                                if (contactType == AuthContactType.Phone) 20 else 254,
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

    /** Keeps form inputs stable during a submission and excludes edits from other routes. */
    private fun editForm(update: AuthUiState.() -> AuthUiState) {
        if (mutableState.value.isProcessing || !isAccountForm()) return
        mutableState.value = mutableState.value.update()
    }

    /** Identifies the routes where contact, name and method choices are editable. */
    private fun isAccountForm(): Boolean =
        mutableState.value.screen == AuthScreen.SignIn ||
            mutableState.value.screen == AuthScreen.CreateAccount

    /** Drops values belonging to the previous contact format without requesting a code. */
    private fun selectContactType(contactType: AuthContactType) {
        if (contactType == mutableState.value.contactType) return
        editForm {
            copy(
                contactType = contactType,
                contact = "",
                password = "",
                isPasswordVisible = false,
                code = "",
                error = null,
                failureCause = null,
                previewReset = false,
            )
        }
    }

    /**
     * Makes the selected method explicit and prevents a hidden password from reaching code flows.
     */
    private fun selectMethod(method: AuthMethod) {
        if (method == mutableState.value.method) return
        editForm {
            copy(
                method = method,
                password = "",
                isPasswordVisible = false,
                code = "",
                error = null,
                failureCause = null,
                previewReset = false,
            )
        }
    }

    /** Preserves password characters exactly and accepts them only in the password form. */
    private fun editPassword(password: String) {
        if (mutableState.value.method != AuthMethod.Password) return
        editForm { copy(password = password, error = null, failureCause = null) }
    }

    /** Keeps leading code zeros without submitting or admitting code input on the account form. */
    private fun editCode(code: String) {
        val state = mutableState.value
        if (state.isProcessing || state.screen != AuthScreen.Verification) return
        mutableState.value =
            state.copy(
                code = code.filter { it in '0'..'9' }.take(6),
                error = null,
                failureCause = null,
            )
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
                        password = "",
                        isPasswordVisible = false,
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
                        state.screen == AuthScreen.Verification && isReset ->
                            resendAccessCode(state.contactType, state.contact)
                        state.screen == AuthScreen.Verification -> completeAccess(state.code)
                        else ->
                            prepareAccess(
                                DemoAccessInput(
                                    state.contactType,
                                    state.contact,
                                    state.method,
                                    state.password,
                                    state.name,
                                    state.isCreatingAccount,
                                ),
                            )
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
            when (result) {
                DemoAccessResult.CodeRequired -> AuthScreen.Verification
                DemoAccessResult.PreviewComplete -> AuthScreen.Complete
                else -> submitted.screen
            }
        val error =
            when (result) {
                DemoAccessResult.InvalidPhone -> AuthError.Phone
                DemoAccessResult.InvalidEmail -> AuthError.Email
                DemoAccessResult.InvalidName -> AuthError.Name
                DemoAccessResult.InvalidPassword -> AuthError.Password
                DemoAccessResult.InvalidCode -> AuthError.Code
                DemoAccessResult.CodeRequired,
                DemoAccessResult.PreviewComplete -> null
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
                password =
                    if (nextScreen == AuthScreen.Verification) "" else mutableState.value.password,
                isPasswordVisible =
                    nextScreen != AuthScreen.Verification && mutableState.value.isPasswordVisible,
                code = if (result == DemoAccessResult.CodeRequired) "" else mutableState.value.code,
            )
    }

    /** Saved flag controls demo routing only and cannot be used as an authorization credential. */
    private companion object {
        const val DEMO_ENTERED = "auth.demoEntered"
    }
}
