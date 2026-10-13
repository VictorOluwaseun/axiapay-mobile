package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.SubmitPayment
import com.kesofty.axiapay.presentation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises real suspension boundaries rather than only immediately completed demo actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class AsyncPaymentTest {
    private val owner = ViewModelStore()

    /** Runs launches immediately until suspension, matching Main.immediate delivery. */
    @Before
    fun setDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Cancels owned work before restoring the platform dispatcher. */
    @After
    fun cleanup() {
        owner.clear()
        Dispatchers.resetMain()
    }

    /** Owns the ViewModel and reviews valid terms without executing the payment. */
    private fun reviewed(viewModel: AxiaViewModel): AxiaViewModel {
        owner.put("axia", viewModel)
        viewModel.accept(AxiaIntent.OpenService("Airtime"))
        viewModel.accept(AxiaIntent.EditPayment(PaymentIntent.Recipient("08000000000")))
        viewModel.accept(AxiaIntent.EditPayment(PaymentIntent.Amount("1000")))
        viewModel.accept(AxiaIntent.EditPayment(PaymentIntent.Review))
        return viewModel
    }

    /** Duplicate taps cannot overlap while an operation suspends; its original ID is retained. */
    @Test
    fun `pending confirmation submits once and connectivity does not replay it`() {
        val fixture = DemoTestFixture()
        val completion = CompletableDeferred<Unit>()
        val ids = mutableListOf<String>()
        val viewModel =
            reviewed(
                fixture.viewModel(
                    submitAction =
                        SubmitPayment { input, id ->
                            ids.add(id)
                            completion.await()
                            fixture.submit(input, id)
                        },
                ),
            )
        val form = (viewModel.uiState.value.navigation.sheet as Sheet.Payment).form
        viewModel.accept(AxiaIntent.ConfirmPayment)
        repeat(3) { viewModel.accept(AxiaIntent.ConfirmPayment) }
        assertTrue(viewModel.uiState.value.isProcessing)
        fixture.connection.isOnline.value = false
        assertTrue(viewModel.uiState.value.session.offline)
        assertEquals(form, (viewModel.uiState.value.navigation.sheet as Sheet.Payment).form)
        fixture.connection.isOnline.value = true
        assertEquals(listOf(form.requestId), ids)
        assertEquals(12_845_000, fixture.session.balance)
        completion.complete(Unit)
        assertFalse(viewModel.uiState.value.isProcessing)
        assertEquals(Sheet.Receipt(form.requestId), viewModel.uiState.value.navigation.sheet)
        assertEquals(12_745_000, fixture.session.balance)
    }

    /**
     * Late success updates the session but cannot replace a new form, even after leaving and
     * returning.
     */
    @Test
    fun `late payment result leaves newer navigation and draft intact`() {
        val fixture = DemoTestFixture()
        val completion = CompletableDeferred<Unit>()
        val viewModel =
            reviewed(
                fixture.viewModel(
                    submitAction =
                        SubmitPayment { input, id ->
                            completion.await()
                            fixture.submit(input, id)
                        },
                ),
            )
        viewModel.accept(AxiaIntent.ConfirmPayment)
        viewModel.accept(AxiaIntent.OpenService("Electricity"))
        viewModel.accept(AxiaIntent.EditPayment(PaymentIntent.Recipient("12345")))
        viewModel.accept(AxiaIntent.SaveDraft)
        val navigation = viewModel.uiState.value.navigation
        completion.complete(Unit)
        assertEquals(navigation, viewModel.uiState.value.navigation)
        assertEquals(12_745_000, viewModel.uiState.value.session.balance)
        assertFalse(viewModel.uiState.value.isProcessing)
    }

    /**
     * Clearing an owner cancels its work; restoration shows the same review without resubmission.
     */
    @Test
    fun `cancellation propagates and restored review never replays work`() {
        val fixture = DemoTestFixture()
        val navigation = MemoryNavigationStore()
        var cancelled = false
        var attempts = 0
        val viewModel =
            reviewed(
                fixture.viewModel(
                    navigation,
                    SubmitPayment { _, _ ->
                        attempts += 1
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    },
                ),
            )
        val before = viewModel.uiState.value.navigation
        viewModel.accept(AxiaIntent.ConfirmPayment)
        owner.clear()
        assertTrue(cancelled)
        assertNull(viewModel.uiState.value.error)
        val restored = fixture.viewModel(navigation)
        owner.put("restored", restored)
        assertEquals(before, restored.uiState.value.navigation)
        assertFalse(restored.uiState.value.isProcessing)
        assertEquals(1, attempts)
        assertEquals(12_845_000, fixture.session.balance)
    }

    /** An unexpected exception releases the busy gate without inventing a failed payment. */
    @Test
    fun `unknown result retains reviewed identity and allows explicit recovery`() {
        val fixture = DemoTestFixture()
        var shouldThrow = true
        val viewModel =
            reviewed(
                fixture.viewModel(
                    submitAction =
                        SubmitPayment { input, id ->
                            if (shouldThrow) throw IllegalStateException("Connection interrupted")
                            fixture.submit(input, id)
                        },
                ),
            )
        val before = viewModel.uiState.value.navigation
        viewModel.accept(AxiaIntent.ConfirmPayment)
        assertFalse(viewModel.uiState.value.isProcessing)
        assertTrue(viewModel.uiState.value.error!!.contains("could not be confirmed"))
        assertEquals(before, viewModel.uiState.value.navigation)
        shouldThrow = false
        viewModel.accept(AxiaIntent.DismissError)
        viewModel.accept(AxiaIntent.ConfirmPayment)
        assertTrue(viewModel.uiState.value.navigation.sheet is Sheet.Receipt)
        assertEquals(12_745_000, fixture.session.balance)
    }

    /** Returning to identical navigation is a new visit and must not consume an old completion. */
    @Test
    fun `leaving and resuming the original review does not consume a stale result`() {
        val fixture = DemoTestFixture()
        val completion = CompletableDeferred<Unit>()
        val viewModel =
            reviewed(
                fixture.viewModel(
                    submitAction =
                        SubmitPayment { input, id ->
                            completion.await()
                            fixture.submit(input, id)
                        },
                ),
            )
        viewModel.accept(AxiaIntent.ConfirmPayment)
        viewModel.accept(AxiaIntent.CloseSheet)
        viewModel.accept(AxiaIntent.ResumeDraft)
        val resumed = viewModel.uiState.value.navigation
        completion.complete(Unit)
        assertEquals(resumed, viewModel.uiState.value.navigation)
        assertEquals(12_745_000, viewModel.uiState.value.session.balance)
    }
}
