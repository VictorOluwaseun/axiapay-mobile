package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.telecom.PrepareTelecom
import com.kesofty.axiapay.domain.telecom.*
import com.kesofty.axiapay.presentation.telecom.*
import java.util.Optional
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises the actual reducer and application workflow with controlled asynchronous ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class TelecomViewModelTest {
    private val owners = ViewModelStore()

    /** Run actions eagerly until a deliberate network suspension. */
    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Cancel timers and outstanding UI jobs before restoring the dispatcher. */
    @After
    fun close() {
        owners.clear()
        Dispatchers.resetMain()
    }

    /** Own every constructed reducer so timer jobs never survive a test. */
    private fun vm(f: TelecomFixture, key: String = "airtime") =
        TelecomViewModel(Optional.of(f.workflow), PrepareTelecom(), f.clock).also {
            owners.put(key, it)
        }

    /** Enter a reviewed form without submitting a payment. */
    private fun review(vm: TelecomViewModel, f: TelecomFixture) {
        vm.accept(TelecomIntent.Bind(f.accountId, "visit-1"))
        vm.accept(TelecomIntent.SelectProduct(f.api.product.id))
        vm.accept(TelecomIntent.EditRecipient("08012345678"))
        vm.accept(TelecomIntent.EditAmount("100"))
        vm.accept(TelecomIntent.Review)
        assertEquals(TelecomScreen.Review, vm.uiState.value.screen)
    }

    /** Duplicate taps, recomposition bindings and route restoration cannot replay consent. */
    @Test
    fun duplicateTapsAndRebindingNeverSubmitTwice() = runTest {
        try {
            val f = TelecomFixture()
            val vm = vm(f)
            review(vm, f)
            f.api.submitGate = CompletableDeferred()
            vm.accept(TelecomIntent.Confirm)
            vm.accept(TelecomIntent.Confirm)
            vm.accept(TelecomIntent.Bind(f.accountId, "visit-1"))
            assertEquals(1, f.api.submissions.size)
            f.api.submitGate!!.complete(Unit)
            assertEquals(TelecomPaymentStatus.Pending, vm.uiState.value.progress?.payment?.status)
            vm.accept(TelecomIntent.Bind(null, null))
            vm.accept(TelecomIntent.Bind(f.accountId, "visit-2"))
            assertEquals(TelecomScreen.History, vm.uiState.value.screen)
            assertEquals(1, f.api.submissions.size)
            assertEquals(0, f.api.reconciles)
            vm.accept(TelecomIntent.CheckSaved(f.store.records.single().requestKey))
            assertEquals(TelecomScreen.Progress, vm.uiState.value.screen)
            assertEquals(1, f.api.submissions.size)
        } finally {
            owners.clear()
        }
    }

    /** A cancellation-resistant response cannot overwrite a newer visit or account's state. */
    @Test
    fun lateQuoteCannotOverwriteNewerVisit() = runTest {
        try {
            val f = TelecomFixture()
            val vm = vm(f)
            vm.accept(TelecomIntent.Bind(f.accountId, "old"))
            vm.accept(TelecomIntent.SelectProduct(f.api.product.id))
            vm.accept(TelecomIntent.EditRecipient("08012345678"))
            vm.accept(TelecomIntent.EditAmount("100"))
            f.api.quoteGate = CompletableDeferred()
            vm.accept(TelecomIntent.Review)
            vm.accept(TelecomIntent.Bind(f.accountId, "new"))
            vm.accept(TelecomIntent.EditRecipient("09012345678"))
            f.api.quoteGate!!.complete(Unit)
            assertEquals("new", vm.uiState.value.routeId)
            assertEquals(TelecomScreen.Form, vm.uiState.value.screen)
            assertEquals("09012345678", vm.uiState.value.recipient)
            assertNull(vm.uiState.value.review)
            assertFalse(vm.uiState.value.isProcessing)
            assertTrue(f.api.submissions.isEmpty())
            val projected = vm.uiState.value.forScope("another-account", "other")
            assertNull(projected.context)
            assertEquals("", projected.recipient)
        } finally {
            owners.clear()
        }
    }

    /** Expiry disables confirmation, and time passing never obtains fresh terms automatically. */
    @Test
    fun quoteExpiryNeedsExplicitFreshReview() = runTest {
        try {
            val f = TelecomFixture()
            val vm = vm(f)
            review(vm, f)
            f.clock.current = f.api.quoted.expiresAt
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(0L, vm.uiState.value.secondsRemaining)
            vm.accept(TelecomIntent.Confirm)
            assertTrue(f.api.submissions.isEmpty())
            assertEquals(1, f.api.quoteCalls)
        } finally {
            owners.clear()
        }
    }

    /** Final receipts require server proof; wallet refresh effects also follow status reads. */
    @Test
    fun lostResponseThenStatusReadProducesRealReceiptAndScopedEffects() = runTest {
        try {
            val f = TelecomFixture()
            val vm = vm(f)
            val effects = mutableListOf<TelecomEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.collect { effects += it }
            }
            review(vm, f)
            f.api.submitFailure = TelecomIssue.Network
            vm.accept(TelecomIntent.Confirm)
            assertNull(vm.uiState.value.progress?.payment)
            vm.accept(TelecomIntent.Done)
            assertFalse(f.store.records.single().acknowledged)
            val request = f.store.records.single()
            f.api.status = TelecomPaymentStatus.Succeeded
            f.api.observed = f.api.payment(request)
            vm.accept(TelecomIntent.CheckStatus)
            assertEquals(TelecomPaymentStatus.Succeeded, vm.uiState.value.progress?.payment?.status)
            vm.accept(TelecomIntent.Done)
            assertTrue(f.store.records.single().acknowledged)
            assertEquals(2, effects.count { it == TelecomEffect.RefreshWallet(f.accountId) })
            val close = effects.filterIsInstance<TelecomEffect.Close>().single()
            assertTrue(vm.isCurrentClose(close))
            vm.accept(TelecomIntent.Bind(null, null))
            vm.accept(TelecomIntent.Bind(f.accountId, "visit-1"))
            assertFalse(vm.isCurrentClose(close))
            assertEquals(1, f.api.submissions.size)
        } finally {
            owners.clear()
        }
    }

    /**
     * Invalid credentials request account restoration instead of leaving a stale signed-in card.
     */
    @Test
    fun invalidSessionRequestsAnAccountScopedRefresh() = runTest {
        try {
            val f = TelecomFixture()
            f.sessions.value = null
            val vm = vm(f)
            val effects = mutableListOf<TelecomEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.collect { effects += it }
            }
            vm.accept(TelecomIntent.Bind(f.accountId, "visit"))
            assertEquals(TelecomScreen.SignIn, vm.uiState.value.screen)
            assertEquals(listOf(TelecomEffect.RefreshWallet(f.accountId)), effects)
            assertTrue(f.api.submissions.isEmpty())
        } finally {
            owners.clear()
        }
    }

    /** Demo composition has no purchase capability, regardless of emitted live intents. */
    @Test
    fun absentFlavorCapabilityCannotExecuteRealActions() {
        val f = TelecomFixture()
        val vm = TelecomViewModel(Optional.empty(), PrepareTelecom(), f.clock)
        owners.put("demo", vm)
        vm.accept(TelecomIntent.Bind(f.accountId, "demo"))
        vm.accept(TelecomIntent.Confirm)
        assertFalse(vm.uiState.value.enabled)
        assertEquals(TelecomScreen.Hidden, vm.uiState.value.screen)
        assertEquals(0, f.api.productsCalls)
    }
}
