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

/** Data plans reuse the real wallet/consent workflow with explicit service and network scope. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataPurchasesTest {
    private val owners = ViewModelStore()

    /** Control presentation IO without platform or DI state. */
    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** End all owner jobs/countdowns after every assertion path. */
    @After
    fun close() {
        owners.clear()
        Dispatchers.resetMain()
    }

    /**
     * A published plan has exact server pricing, allowance and duration, never demo catalogue IDs.
     */
    private fun fixture() =
        PurchaseFixture().apply {
            val plan =
                api.product.copy(
                    id = "123e4567-e89b-42d3-a456-426614174020",
                    displayName = "MTN 1 GB monthly",
                    fixedMinorUnits = 125050.toBigInteger(),
                    minimumMinorUnits = 125050.toBigInteger(),
                    maximumMinorUnits = 125050.toBigInteger(),
                    stepMinorUnits = 1.toBigInteger(),
                    service = PurchaseService.Data,
                    allowanceLabel = "1 GB",
                    validitySeconds = 2592000,
                )
            api.catalogue = listOf(plan)
            api.quoted =
                api.quoted.copy(
                    id = "123e4567-e89b-42d3-a456-426614174021",
                    productId = plan.id,
                    displayName = plan.displayName,
                    principalMinorUnits = plan.fixedMinorUnits!!,
                    totalDebitMinorUnits = plan.fixedMinorUnits!! + plan.feeMinorUnits,
                    service = plan.service,
                    allowanceLabel = plan.allowanceLabel,
                    validitySeconds = plan.validitySeconds,
                )
        }

    /** Prepare a fixed plan once: the selected product determines price, not entered text. */
    private fun input(f: PurchaseFixture) =
        requireNotNull(PreparePurchase()(f.api.catalogue.first(), "0801 234-5678", "999999").input)

    /** Construct the actual reducer/workflow with a test lifecycle owner. */
    private fun vm(f: PurchaseFixture) =
        PurchaseViewModel(Optional.of(f.workflow), PreparePurchase(), f.clock).also {
            owners.put("data", it)
        }

    /** Select the explicit network and plan and obtain server review without spending. */
    private fun review(vm: PurchaseViewModel, f: PurchaseFixture) {
        vm.accept(PurchaseIntent.Bind(f.accountId, "visit", PurchaseService.Data))
        vm.accept(PurchaseIntent.SelectProvider("mtn"))
        vm.accept(PurchaseIntent.SelectProduct(f.api.catalogue.first().id))
        vm.accept(PurchaseIntent.EditRecipient("08012345678"))
        vm.accept(PurchaseIntent.Review)
        assertEquals(PurchaseScreen.Review, vm.uiState.value.screen)
    }

    /**
     * Catalogue and owned wallet reads produce the exact selected plan without an amount override.
     */
    @Test
    fun fixedPlanReviewUsesOwnedWalletAndPublishedTerms() = runTest {
        val f = fixture()
        val context = f.workflow.load(f.accountId, PurchaseService.Data)
        assertEquals(PurchaseService.Data, f.api.requestedService)
        assertEquals(listOf(f.api.catalogue.first()), context.products)
        val prepared = input(f)
        assertNull(prepared.amountMinorUnits)
        assertEquals(PurchaseService.Data, prepared.service)
        val reviewed = f.workflow.review(f.accountId, prepared)
        assertEquals(f.wallets.wallet.id, f.api.quoteWallet)
        assertEquals("125050", reviewed.quote.principalMinorUnits.toString())
        assertEquals("1 GB", reviewed.quote.allowanceLabel)
        assertEquals(2592000L, reviewed.quote.validitySeconds)
        assertEquals(PurchaseService.Data, reviewed.request.service)
        assertTrue(f.api.submissions.isEmpty())
    }

    /** A data screen cannot display or select another service's saved references. */
    @Test
    fun savedReferencesStayAccountApiAndServiceScoped() = runTest {
        val f = fixture()
        val data = f.workflow.review(f.accountId, input(f)).request
        val airtime =
            data.copy(
                requestKey = "123e4567-e89b-42d3-a456-426614174022",
                service = PurchaseService.Airtime,
            )
        val foreign =
            data.copy(owner = data.owner.copy(accountId = "123e4567-e89b-42d3-a456-426614174099"))
        f.store.records = listOf(data, airtime, foreign)
        assertEquals(listOf(data), f.workflow.load(f.accountId, PurchaseService.Data).requests)
        assertEquals(
            listOf(airtime),
            f.workflow.load(f.accountId, PurchaseService.Airtime).requests,
        )
        f.api.status = PurchasePaymentStatus.Succeeded
        f.api.observed =
            f.api.payment(data).copy(quote = f.api.quoted.copy(service = PurchaseService.Airtime))
        failure(PurchaseIssue.InvalidResponse) { f.workflow.recover(f.accountId, data) }
        failure(PurchaseIssue.InvalidResponse) { f.workflow.reconcile(f.accountId, data) }
        assertEquals(0, f.api.reconciles)
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Wrong-service quotes and forged reviews cannot reach purchase submission. */
    @Test
    fun quoteAndRequestServiceMustMatchBeforeSubmission() = runTest {
        val f = fixture()
        val reviewed = f.workflow.review(f.accountId, input(f))
        failure(PurchaseIssue.InvalidResponse) {
            f.workflow.submit(
                f.accountId,
                reviewed.copy(request = reviewed.request.copy(service = PurchaseService.Airtime)),
            )
        }
        f.api.quoted = f.api.quoted.copy(service = PurchaseService.Airtime)
        failure(PurchaseIssue.InvalidResponse) { f.workflow.review(f.accountId, input(f)) }
        assertTrue(f.api.submissions.isEmpty())
        assertTrue(f.store.records.isEmpty())
    }

    /**
     * Unknown responses retain data identity; display outages never block explicit status recovery.
     */
    @Test
    fun uncertainDataPurchaseKeepsOriginalKeyAndRecoveryDuringOutages() = runTest {
        val f = fixture()
        val review = f.workflow.review(f.accountId, input(f))
        f.api.submitFailure = PurchaseIssue.Network
        assertNull(f.workflow.submit(f.accountId, review).payment)
        f.api.catalogueFailure = PurchaseIssue.Unavailable
        assertEquals(
            listOf(review.request),
            f.workflow.load(f.accountId, PurchaseService.Data).requests,
        )
        assertEquals(PurchaseIssue.NotFound, f.workflow.recover(f.accountId, review.request).issue)
        assertEquals(1, f.api.submissions.size)
        f.api.submitFailure = null
        f.api.status = PurchasePaymentStatus.Succeeded
        val result = f.workflow.retrySubmission(f.accountId, review.request)
        assertEquals(PurchaseService.Data, result.payment?.quote?.service)
        assertEquals(listOf(review.request, review.request), f.api.submissions)
        f.workflow.acknowledge(result)
        assertTrue(f.store.records.single().acknowledged)
        assertEquals(1, f.api.quoteCalls)
    }

    /** A network change clears the selected plan/amount before review can be requested. */
    @Test
    fun changingNetworkCannotSubmitAHiddenPlan() = runTest {
        try {
            val f = fixture()
            val second =
                f.api.catalogue
                    .first()
                    .copy(id = "123e4567-e89b-42d3-a456-426614174023", providerCode = "airtel")
            f.api.catalogue = f.api.catalogue + second
            val vm = vm(f)
            vm.accept(PurchaseIntent.Bind(f.accountId, "visit", PurchaseService.Data))
            vm.accept(PurchaseIntent.SelectProduct(f.api.catalogue.first().id))
            assertNull(vm.uiState.value.selectedProduct)
            vm.accept(PurchaseIntent.SelectProvider("mtn"))
            vm.accept(PurchaseIntent.SelectProduct(f.api.catalogue.first().id))
            vm.accept(PurchaseIntent.EditRecipient("08012345678"))
            assertNotNull(vm.uiState.value.prepared)
            vm.accept(PurchaseIntent.SelectProvider("mtn"))
            assertNotNull(vm.uiState.value.prepared)
            vm.accept(PurchaseIntent.SelectProvider("airtel"))
            assertNull(vm.uiState.value.selectedProduct)
            assertNull(vm.uiState.value.prepared)
            vm.accept(PurchaseIntent.SelectProduct(f.api.catalogue.first().id))
            vm.accept(PurchaseIntent.Review)
            assertEquals(0, f.api.quoteCalls)
            assertEquals(listOf(second), vm.uiState.value.visibleProducts)
        } finally {
            owners.clear()
        }
    }

    /** Late data quote completion cannot overwrite a same-ID airtime route. */
    @Test
    fun serviceSwitchRejectsLateQuotesAndDropsPrivateInput() = runTest {
        try {
            val f = fixture()
            val vm = vm(f)
            f.api.quoteGate = CompletableDeferred()
            vm.accept(PurchaseIntent.Bind(f.accountId, "same", PurchaseService.Data))
            vm.accept(PurchaseIntent.SelectProvider("mtn"))
            vm.accept(PurchaseIntent.SelectProduct(f.api.catalogue.first().id))
            vm.accept(PurchaseIntent.EditRecipient("08012345678"))
            vm.accept(PurchaseIntent.Review)
            vm.accept(PurchaseIntent.Bind(f.accountId, "same", PurchaseService.Airtime))
            f.api.quoteGate!!.complete(Unit)
            assertEquals(PurchaseService.Airtime, vm.uiState.value.service)
            assertEquals(PurchaseScreen.Form, vm.uiState.value.screen)
            assertEquals("", vm.uiState.value.recipient)
            assertNull(vm.uiState.value.review)
            assertNull(vm.uiState.value.forScope(f.accountId, "same", PurchaseService.Data).context)
            assertTrue(f.api.submissions.isEmpty())
        } finally {
            owners.clear()
        }
    }

    /** Duplicate data confirmation and reopening use one saved identity and never replay a POST. */
    @Test
    fun dataConfirmationAndRestorationAreIdempotent() = runTest {
        try {
            val f = fixture()
            val vm = vm(f)
            val effects = mutableListOf<PurchaseEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.collect { effects += it }
            }
            review(vm, f)
            f.api.submitGate = CompletableDeferred()
            vm.accept(PurchaseIntent.Confirm)
            vm.accept(PurchaseIntent.Confirm)
            assertEquals(1, f.api.submissions.size)
            f.api.submitGate!!.complete(Unit)
            assertTrue(effects.contains(PurchaseEffect.RefreshWallet(f.accountId)))
            assertEquals(PurchasePaymentStatus.Pending, vm.uiState.value.progress?.payment?.status)
            vm.accept(PurchaseIntent.Bind(null, null))
            vm.accept(PurchaseIntent.Bind(f.accountId, "new", PurchaseService.Data))
            assertEquals(PurchaseScreen.History, vm.uiState.value.screen)
            assertEquals(1, f.api.submissions.size)
            assertEquals(0, f.api.reconciles)
        } finally {
            owners.clear()
        }
    }

    /** Known classifications remain distinct without exposing provider bodies. */
    private suspend fun failure(expected: PurchaseIssue, operation: suspend () -> Any?) {
        try {
            operation()
            fail("Expected $expected")
        } catch (failure: PurchaseFailure) {
            assertEquals(expected, failure.issue)
        }
    }
}
