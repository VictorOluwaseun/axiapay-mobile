package com.kesofty.axiapay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.CompleteDemoAccess
import com.kesofty.axiapay.application.PrepareDemoAccess
import com.kesofty.axiapay.domain.DemoAccessRepository
import com.kesofty.axiapay.domain.DemoAccessResult
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
        AuthViewModel(handle, PrepareDemoAccess(repository), CompleteDemoAccess(repository)).also {
            owner.put("auth", it)
        }

    /**
     * Invalid inputs never reach the adapter; normalized phone and leading code zeros are
     * preserved.
     */
    @Test
    fun `use cases validate and normalize without changing code meaning`() = runTest {
        val repository = RecordingAccessRepository()
        val prepare = PrepareDemoAccess(repository)
        assertEquals(DemoAccessResult.InvalidPhone, prepare("123", "", false))
        assertEquals(DemoAccessResult.InvalidName, prepare("08012345678", " ", true))
        assertEquals(0, repository.preparations)
        assertEquals(DemoAccessResult.PreviewReady, prepare("+234 801 234 5678", "Ada", true))
        assertEquals("+2348012345678", repository.lastPhone)
        val complete = CompleteDemoAccess(repository)
        assertEquals(DemoAccessResult.InvalidCode, complete("12345"))
        assertEquals(DemoAccessResult.InvalidCode, complete("１２３４５６"))
        assertEquals(DemoAccessResult.PreviewReady, complete("001234"))
        assertEquals("001234", repository.lastCode)
    }

    /** A second tap is ignored; navigating back cancels work and rejects late completion. */
    @Test
    fun `repeated continue cannot overlap and back owns navigation`() {
        val repository = RecordingAccessRepository().apply { gate = CompletableDeferred() }
        val vm = viewModel(repository)
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditPhone("08012345678"))
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
        vm.accept(AuthIntent.EditPhone("08012345678"))
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.EditCode("001234"))
        assertNull(repository.lastCode)
        vm.accept(AuthIntent.ResetCodePreview)
        assertEquals("", vm.uiState.value.code)
        assertTrue(vm.uiState.value.previewReset)
        vm.accept(AuthIntent.EditCode("001234"))
        vm.accept(AuthIntent.Back)
        assertEquals("", vm.uiState.value.code)
        assertEquals("08012345678", vm.uiState.value.phoneNumber)
        vm.accept(AuthIntent.Continue)
        vm.accept(AuthIntent.EditCode("001234"))
        vm.accept(AuthIntent.Continue)
        assertEquals(AuthScreen.Complete, vm.uiState.value.screen)
        assertEquals("", vm.uiState.value.code)
        assertEquals("", vm.uiState.value.phoneNumber)
    }

    /** Process restoration saves no form inputs and cannot replay challenge requests. */
    @Test
    fun `saved state contains only demo entry and never personal fields or code`() {
        val repository = RecordingAccessRepository()
        val handle = SavedStateHandle()
        val vm = viewModel(repository, handle)
        vm.accept(AuthIntent.CreateAccount)
        vm.accept(AuthIntent.EditName("Ada"))
        vm.accept(AuthIntent.EditPhone("08012345678"))
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
        restored.accept(AuthIntent.PreviewAuth)
        assertEquals(AuthScreen.Welcome, restored.uiState.value.screen)
    }

    /** Unexpected failures retain their cause and release the gate for an explicit retry. */
    @Test
    fun `failed preview remains on the form and supports retry`() {
        val failure = IllegalStateException("Preview unavailable")
        val repository = RecordingAccessRepository().apply { exception = failure }
        val vm = viewModel(repository)
        vm.accept(AuthIntent.SignIn)
        vm.accept(AuthIntent.EditPhone("08012345678"))
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
}

/**
 * Controlled in-memory test adapter records only synthetic inputs and provides a suspension gate.
 */
private class RecordingAccessRepository : DemoAccessRepository {
    var preparations = 0
    var lastPhone: String? = null
    var lastCode: String? = null
    var gate: CompletableDeferred<Unit>? = null
    var exception: Exception? = null

    /** Captures the normalized input and waits until the test allows completion. */
    override suspend fun preparePreview(phoneNumber: String): DemoAccessResult {
        preparations += 1
        lastPhone = phoneNumber
        gate?.await()
        exception?.let { throw it }
        return DemoAccessResult.PreviewReady
    }

    /** Records exact code characters so leading-zero preservation is observable. */
    override suspend fun completePreview(code: String): DemoAccessResult {
        lastCode = code
        return DemoAccessResult.PreviewReady
    }
}
