package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.purchases.PreparePurchase
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.presentation.purchases.*
import java.util.Optional
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises the actual reducer and application workflow with controlled asynchronous ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseViewModelTest {
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
    private fun vm(f: PurchaseFixture, key: String = "airtime") =
        PurchaseViewModel(Optional.of(f.workflow), PreparePurchase(), f.clock).also {
            it.accept(
                PurchaseIntent.UpdateFeatures(
                    com.kesofty.axiapay.domain.config.PaymentFeatures.AllEnabled,
                ),
            )
            owners.put(key, it)
        }

    /** Enter a reviewed form without submitting a payment. */
    private fun review(vm: PurchaseViewModel, f: PurchaseFixture) {
        vm.accept(PurchaseIntent.Bind(f.accountId, "visit-1"))
        vm.accept(PurchaseIntent.SelectProduct(f.api.product.id))
        vm.accept(PurchaseIntent.EditRecipient("08012345678"))
        vm.accept(PurchaseIntent.EditAmount("100"))
        vm.accept(PurchaseIntent.Review)
        assertEquals(PurchaseScreen.Review, vm.uiState.value.screen)
    }

    /** Duplicate taps, recomposition bindings and route restoration cannot replay consent. */
    @Test
    fun duplicateTapsAndRebindingNeverSubmitTwice() = runTest {
        try {
            val f = PurchaseFixture()
            val vm = vm(f)
            review(vm, f)
            f.api.submitGate = CompletableDeferred()
            vm.accept(PurchaseIntent.Confirm)
            vm.accept(PurchaseIntent.Confirm)
            vm.accept(PurchaseIntent.Bind(f.accountId, "visit-1"))
            assertEquals(1, f.api.submissions.size)
            f.api.submitGate!!.complete(Unit)
            assertEquals(PurchasePaymentStatus.Pending, vm.uiState.value.progress?.payment?.status)
            vm.accept(PurchaseIntent.Bind(null, null))
            vm.accept(PurchaseIntent.Bind(f.accountId, "visit-2"))
            assertEquals(PurchaseScreen.History, vm.uiState.value.screen)
            assertEquals(1, f.api.submissions.size)
            assertEquals(0, f.api.reconciles)
            vm.accept(PurchaseIntent.CheckSaved(f.store.records.single().requestKey))
            assertEquals(PurchaseScreen.Progress, vm.uiState.value.screen)
            assertEquals(1, f.api.submissions.size)
        } finally {
            owners.clear()
        }
    }

    /** A cancellation-resistant response cannot overwrite a newer visit or account's state. */
    @Test
    fun lateQuoteCannotOverwriteNewerVisit() = runTest {
        try {
            val f = PurchaseFixture()
            val vm = vm(f)
            vm.accept(PurchaseIntent.Bind(f.accountId, "old"))
            vm.accept(PurchaseIntent.SelectProduct(f.api.product.id))
            vm.accept(PurchaseIntent.EditRecipient("08012345678"))
            vm.accept(PurchaseIntent.EditAmount("100"))
            f.api.quoteGate = CompletableDeferred()
            vm.accept(PurchaseIntent.Review)
            vm.accept(PurchaseIntent.Bind(f.accountId, "new"))
            vm.accept(PurchaseIntent.EditRecipient("09012345678"))
            f.api.quoteGate!!.complete(Unit)
            assertEquals("new", vm.uiState.value.routeId)
            assertEquals(PurchaseScreen.Form, vm.uiState.value.screen)
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
            val f = PurchaseFixture()
            val vm = vm(f)
            review(vm, f)
            f.clock.current = f.api.quoted.expiresAt
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(0L, vm.uiState.value.secondsRemaining)
            vm.accept(PurchaseIntent.Confirm)
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
            val f = PurchaseFixture()
            val vm = vm(f)
            val effects = mutableListOf<PurchaseEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.collect { effects += it }
            }
            review(vm, f)
            f.api.submitFailure = PurchaseIssue.Network
            vm.accept(PurchaseIntent.Confirm)
            assertNull(vm.uiState.value.progress?.payment)
            vm.accept(PurchaseIntent.Done)
            assertFalse(f.store.records.single().acknowledged)
            val request = f.store.records.single()
            f.api.status = PurchasePaymentStatus.Succeeded
            f.api.observed = f.api.payment(request)
            vm.accept(PurchaseIntent.CheckStatus)
            assertEquals(
                PurchasePaymentStatus.Succeeded,
                vm.uiState.value.progress?.payment?.status,
            )
            vm.accept(PurchaseIntent.Done)
            assertTrue(f.store.records.single().acknowledged)
            assertEquals(2, effects.count { it == PurchaseEffect.RefreshWallet(f.accountId) })
            val close = effects.filterIsInstance<PurchaseEffect.Close>().single()
            assertTrue(vm.isCurrentClose(close))
            vm.accept(PurchaseIntent.Bind(null, null))
            vm.accept(PurchaseIntent.Bind(f.accountId, "visit-1"))
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
            val f = PurchaseFixture()
            f.sessions.value = null
            val vm = vm(f)
            val effects = mutableListOf<PurchaseEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.collect { effects += it }
            }
            vm.accept(PurchaseIntent.Bind(f.accountId, "visit"))
            assertEquals(PurchaseScreen.SignIn, vm.uiState.value.screen)
            assertEquals(listOf(PurchaseEffect.RefreshWallet(f.accountId)), effects)
            assertTrue(f.api.submissions.isEmpty())
        } finally {
            owners.clear()
        }
    }

    /**
     * A refresh cannot replay consent; disablement retains the review and accepted progress remains
     * recoverable.
     */
    @Test
    fun featureChangesGateNewConsentAndPreserveRecovery() = runTest {
        val f = PurchaseFixture()
        val vm = vm(f)
        review(vm, f)
        val original = vm.uiState.value.review
        val disabled =
            com.kesofty.axiapay.domain.config.PaymentFeatures.AllEnabled.copy(airtime = false)
        vm.accept(PurchaseIntent.UpdateFeatures(disabled))
        vm.accept(PurchaseIntent.Confirm)
        assertEquals(original, vm.uiState.value.review)
        assertTrue(f.api.submissions.isEmpty())
        vm.accept(
            PurchaseIntent.UpdateFeatures(
                com.kesofty.axiapay.domain.config.PaymentFeatures.AllEnabled,
            ),
        )
        assertTrue(f.api.submissions.isEmpty())
        vm.accept(PurchaseIntent.Confirm)
        assertEquals(1, f.api.submissions.size)
        vm.accept(PurchaseIntent.UpdateFeatures(disabled))
        vm.accept(PurchaseIntent.CheckStatus)
        assertNotNull(vm.uiState.value.progress?.payment)
        assertFalse(vm.uiState.value.featureAvailable)
        assertEquals(1, f.api.submissions.size)
        vm.accept(PurchaseIntent.History)
        vm.accept(PurchaseIntent.CheckSaved(f.store.records.single().requestKey))
        assertEquals(PurchaseScreen.Progress, vm.uiState.value.screen)
    }

    /** Demo composition has no purchase capability, regardless of emitted live intents. */
    @Test
    fun absentFlavorCapabilityCannotExecuteRealActions() {
        val f = PurchaseFixture()
        val vm = PurchaseViewModel(Optional.empty(), PreparePurchase(), f.clock)
        owners.put("demo", vm)
        vm.accept(PurchaseIntent.Bind(f.accountId, "demo"))
        vm.accept(PurchaseIntent.Confirm)
        assertFalse(vm.uiState.value.enabled)
        assertEquals(PurchaseScreen.Hidden, vm.uiState.value.screen)
        assertEquals(0, f.api.productsCalls)
    }
}
