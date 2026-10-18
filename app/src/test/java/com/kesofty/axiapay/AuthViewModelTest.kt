package com.kesofty.axiapay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.application.auth.DemoAccountAccess
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.auth.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises demo validation, async ownership, code clearing and non-sensitive restoration. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val owner = ViewModelStore()

    /** Executes main launches immediately until a controlled suspension boundary. */
    @Before
    fun setMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Cancels any pending preview before releasing the test dispatcher. */
    @After
    fun cleanup() {
        owner.clear()
        Dispatchers.resetMain()
    }

    /** Owns a preview ViewModel whose adapter can be paused or failed by the test. */
    private fun viewModel(
        repository: DemoAccessRepository,
        handle: SavedStateHandle = SavedStateHandle(),
    ): AuthViewModel =
        AuthViewModel(
                handle,
                DemoAccountAccess(
                    PrepareDemoAccess(repository),
                    CompleteDemoAccess(repository),
                    ResendDemoAccessCode(repository),
                ),
                java.time.Clock.systemUTC(),
                enabledRemoteConfiguration(),
            )
            .also {
                owner.put("auth", it)
            }

    /** A second tap is ignored; navigating back cancels work and rejects late completion. */
    @Test
    fun `repeated continue cannot overlap and back owns navigation`() {
        val repository = RecordingAccessRepository().apply { gate = CompletableDeferred() }
        val vm = viewModel(repository)
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.Continue)
        assertEquals(1, repository.preparations)
        assertTrue(vm.uiState.value.isProcessing)
        vm.accept(AuthIntent.Back)
        repository.gate!!.complete(Unit)
        assertEquals(AuthScreen.Welcome, vm.uiState.value.screen)
        assertFalse(vm.uiState.value.isProcessing)
        assertNull(vm.uiState.value.error)
    }

    /** Code input never auto-submits; resend, back and success each clear it explicitly. */
    @Test
    fun `verification clearing never submits implicitly`() {
        val repository = RecordingAccessRepository()
        val vm = viewModel(repository)
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.EditCode("001234"))
        assertNull(repository.lastCode)
        vm.accept(AuthIntent.ResendCode)
        assertEquals("", vm.uiState.value.code)
        assertTrue(vm.uiState.value.isCodeResent)
        vm.accept(AuthIntent.EditCode("001234"))
        vm.accept(AuthIntent.Back)
        assertEquals("", vm.uiState.value.code)
        assertEquals("08012345678", vm.uiState.value.contact)
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.EditCode("001234"))
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.Complete, vm.uiState.value.screen)
        assertEquals("", vm.uiState.value.code)
        assertEquals("", vm.uiState.value.contact)
    }

    /** Process restoration saves no form inputs and cannot replay challenge requests. */
    @Test
    fun `saved state contains only demo entry and never personal fields or code`() {
        val repository = RecordingAccessRepository()
        val handle = SavedStateHandle()
        val vm = viewModel(repository, handle)
        vm.accept(AuthIntent.CreateAccount)
        vm.accept(AuthIntent.EditName("Ada"))
        vm.accept(AuthIntent.SelectContactType(AuthContactType.Email))
        vm.accept(AuthIntent.EditContact("Ada@example.com"))
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditPassword("synthetic password"))
        assertTrue(handle.keys().isEmpty())
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.EditCode("001234"))
        assertTrue(handle.keys().isEmpty())
        val restored = viewModel(repository, SavedStateHandle())
        assertEquals(AuthUiState(), restored.uiState.value)
        assertEquals(1, repository.preparations)
        restored.accept(AuthIntent.ExploreDemo)
        assertEquals(AuthScreen.Dashboard, restored.uiState.value.screen)
    }

    /** The saved entry flag restores navigation only, without starting any repository operation. */
    @Test
    fun `demo entry restores without pretending to authenticate`() {
        val repository = RecordingAccessRepository()
        val handle = SavedStateHandle()
        viewModel(repository, handle).accept(AuthIntent.ExploreDemo)
        val snapshot = handle.keys().associateWith { handle.get<Any?>(it) }
        assertEquals(listOf(true), snapshot.values.toList())
        val restored = viewModel(repository, SavedStateHandle(snapshot))
        assertEquals(AuthScreen.Dashboard, restored.uiState.value.screen)
        assertEquals(0, repository.preparations)
        restored.accept(AuthIntent.OpenAccount)
        assertEquals(AuthScreen.Welcome, restored.uiState.value.screen)
    }

    /** Unexpected failures retain their cause and release the gate for an explicit retry. */
    @Test
    fun `failed preview remains on the form and supports retry`() {
        val failure = IllegalStateException("Preview unavailable")
        val repository = RecordingAccessRepository().apply { exception = failure }
        val vm = viewModel(repository)
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.SignIn, vm.uiState.value.screen)
        assertEquals(AuthError.Unavailable, vm.uiState.value.error)
        assertSame(failure, vm.uiState.value.failureCause)
        assertFalse(vm.uiState.value.isProcessing)
        repository.exception = null
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.Verification, vm.uiState.value.screen)
        assertNull(vm.uiState.value.failureCause)
    }

    /**
     * Contact/method changes clear credentials without sending a hidden password or starting work.
     */
    @Test
    fun `method and contact selection clear transient credentials without submitting`() {
        val repository = RecordingAccessRepository()
        val vm = viewModel(repository)
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditPassword(" sample password "))
        vm.accept(AuthIntent.TogglePasswordVisibility)
        assertTrue(vm.uiState.value.isPasswordVisible)
        vm.accept(AuthIntent.SelectMethod(AuthMethod.OneTimeCode))
        assertEquals("", vm.uiState.value.password)
        assertFalse(vm.uiState.value.isPasswordVisible)
        assertEquals("08012345678", vm.uiState.value.contact)
        vm.accept(AuthIntent.EditPassword("hidden password"))
        assertEquals("", vm.uiState.value.password)
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditPassword("sample password"))
        vm.accept(AuthIntent.SelectContactType(AuthContactType.Email))
        assertEquals("", vm.uiState.value.contact)
        assertEquals("", vm.uiState.value.password)
        assertEquals(AuthContactType.Email, vm.uiState.value.contactType)
        assertEquals(0, repository.preparations)
    }

    /**
     * Signup always verifies; resend uses the destination after the submitted password is
     * discarded.
     */
    @Test
    fun `password signup verifies and resends without retaining the password`() {
        val repository = RecordingAccessRepository()
        val vm = viewModel(repository)
        vm.accept(AuthIntent.CreateAccount)
        vm.accept(AuthIntent.SelectContactType(AuthContactType.Email))
        vm.accept(AuthIntent.EditContact("Ada@example.com"))
        vm.accept(AuthIntent.EditName("Ada"))
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditPassword(" sample password "))
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.Verification, vm.uiState.value.screen)
        assertEquals(" sample password ", repository.lastInput?.password)
        assertEquals("", vm.uiState.value.password)
        vm.accept(AuthIntent.EditCode("001234"))
        vm.accept(AuthIntent.ResendCode)
        assertEquals(1, repository.resends)
        assertEquals("Ada@example.com", repository.lastResendContact)
        assertEquals(1, repository.preparations)
        assertEquals("", vm.uiState.value.code)
        vm.accept(AuthIntent.SelectMethod(AuthMethod.OneTimeCode))
        vm.accept(AuthIntent.EditPassword("not a form"))
        assertEquals(AuthMethod.Password, vm.uiState.value.method)
        assertEquals("", vm.uiState.value.password)
        vm.accept(AuthIntent.Back)
        assertEquals(AuthScreen.CreateAccount, vm.uiState.value.screen)
        assertEquals("Ada@example.com", vm.uiState.value.contact)
        assertEquals("", vm.uiState.value.password)
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthError.Password, vm.uiState.value.error)
    }

    /**
     * Password sign-in completes directly, clearing inputs without requesting or verifying a code.
     */
    @Test
    fun `password sign in completes directly and clears all form inputs`() {
        val repository = RecordingAccessRepository()
        val vm = viewModel(repository)
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditPassword(" demo pass "))
        vm.accept(AuthIntent.TogglePasswordVisibility)
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.Complete, vm.uiState.value.screen)
        assertEquals("", vm.uiState.value.contact)
        assertEquals("", vm.uiState.value.password)
        assertFalse(vm.uiState.value.isPasswordVisible)
        assertNull(repository.lastCode)
        assertEquals(0, repository.resends)
    }

    /**
     * A busy submission retains its captured method/contact despite attempted edits or selection
     * changes.
     */
    @Test
    fun `pending password sign in locks its form and navigation discards credentials`() {
        val repository = RecordingAccessRepository().apply { gate = CompletableDeferred() }
        val vm = viewModel(repository)
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.SelectMethod(AuthMethod.Password))
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.EditPassword("demo password"))
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.SelectContactType(AuthContactType.Email))
        vm.accept(AuthIntent.SelectMethod(AuthMethod.OneTimeCode))
        vm.accept(AuthIntent.EditPassword("different password"))
        vm.accept(AuthIntent.TogglePasswordVisibility)
        vm.accept(AuthIntent.Continue)
        assertEquals(1, repository.preparations)
        assertEquals(AuthContactType.Phone, vm.uiState.value.contactType)
        assertEquals(AuthMethod.Password, vm.uiState.value.method)
        assertEquals("demo password", vm.uiState.value.password)
        assertFalse(vm.uiState.value.isPasswordVisible)
        vm.accept(AuthIntent.Back)
        assertEquals("", vm.uiState.value.password)
        repository.gate!!.complete(Unit)
        assertEquals(AuthScreen.Welcome, vm.uiState.value.screen)
        assertNull(vm.uiState.value.error)
    }

    /** Failed resend clears the entered code and releases its gate for another explicit request. */
    @Test
    fun `resend failure retains verification destination and allows a retry`() {
        val repository = RecordingAccessRepository()
        val vm = viewModel(repository)
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditContact("08012345678"))
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.EditCode("001234"))
        val failure = IllegalStateException("Synthetic resend failure")
        repository.exception = failure
        vm.accept(AuthIntent.ResendCode)
        assertEquals(AuthScreen.Verification, vm.uiState.value.screen)
        assertEquals("", vm.uiState.value.code)
        assertEquals("08012345678", vm.uiState.value.contact)
        assertSame(failure, vm.uiState.value.failureCause)
        repository.exception = null
        vm.accept(AuthIntent.ResendCode)
        assertTrue(vm.uiState.value.isCodeResent)
        assertNull(vm.uiState.value.failureCause)
        assertEquals(2, repository.resends)
    }
}
