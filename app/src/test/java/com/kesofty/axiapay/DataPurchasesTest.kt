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
        TelecomFixture().apply {
            val plan =
                api.product.copy(
                    id = "123e4567-e89b-42d3-a456-426614174020",
                    displayName = "MTN 1 GB monthly",
                    fixedMinorUnits = 125050.toBigInteger(),
                    minimumMinorUnits = 125050.toBigInteger(),
                    maximumMinorUnits = 125050.toBigInteger(),
                    stepMinorUnits = 1.toBigInteger(),
                    service = TelecomService.Data,
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
    private fun input(f: TelecomFixture) =
        requireNotNull(PrepareTelecom()(f.api.catalogue.first(), "0801 234-5678", "999999").input)

    /** Construct the actual reducer/workflow with a test lifecycle owner. */
    private fun vm(f: TelecomFixture) =
        TelecomViewModel(Optional.of(f.workflow), PrepareTelecom(), f.clock).also {
            owners.put("data", it)
        }

    /** Select the explicit network and plan and obtain server review without spending. */
    private fun review(vm: TelecomViewModel, f: TelecomFixture) {
        vm.accept(TelecomIntent.Bind(f.accountId, "visit", TelecomService.Data))
        vm.accept(TelecomIntent.SelectNetwork("mtn"))
        vm.accept(TelecomIntent.SelectProduct(f.api.catalogue.first().id))
        vm.accept(TelecomIntent.EditRecipient("08012345678"))
        vm.accept(TelecomIntent.Review)
        assertEquals(TelecomScreen.Review, vm.uiState.value.screen)
    }

    /**
     * Catalogue and owned wallet reads produce the exact selected plan without an amount override.
     */
    @Test
    fun fixedPlanReviewUsesOwnedWalletAndPublishedTerms() = runTest {
        val f = fixture()
        val context = f.workflow.load(f.accountId, TelecomService.Data)
        assertEquals(TelecomService.Data, f.api.requestedService)
        assertEquals(listOf(f.api.catalogue.first()), context.products)
        val prepared = input(f)
        assertNull(prepared.amountMinorUnits)
        assertEquals(TelecomService.Data, prepared.service)
        val reviewed = f.workflow.review(f.accountId, prepared)
        assertEquals(f.wallets.wallet.id, f.api.quoteWallet)
        assertEquals("125050", reviewed.quote.principalMinorUnits.toString())
        assertEquals("1 GB", reviewed.quote.allowanceLabel)
        assertEquals(2592000L, reviewed.quote.validitySeconds)
        assertEquals(TelecomService.Data, reviewed.request.service)
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
                service = TelecomService.Airtime,
            )
        val foreign =
            data.copy(owner = data.owner.copy(accountId = "123e4567-e89b-42d3-a456-426614174099"))
        f.store.records = listOf(data, airtime, foreign)
        assertEquals(listOf(data), f.workflow.load(f.accountId, TelecomService.Data).requests)
        assertEquals(listOf(airtime), f.workflow.load(f.accountId, TelecomService.Airtime).requests)
        f.api.status = TelecomPaymentStatus.Succeeded
        f.api.observed =
            f.api.payment(data).copy(quote = f.api.quoted.copy(service = TelecomService.Airtime))
        failure(TelecomIssue.InvalidResponse) { f.workflow.recover(f.accountId, data) }
        failure(TelecomIssue.InvalidResponse) { f.workflow.reconcile(f.accountId, data) }
        assertEquals(0, f.api.reconciles)
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Wrong-service quotes and forged reviews cannot reach purchase submission. */
    @Test
    fun quoteAndRequestServiceMustMatchBeforeSubmission() = runTest {
        val f = fixture()
        val reviewed = f.workflow.review(f.accountId, input(f))
        failure(TelecomIssue.InvalidResponse) {
            f.workflow.submit(
                f.accountId,
                reviewed.copy(request = reviewed.request.copy(service = TelecomService.Airtime)),
            )
        }
        f.api.quoted = f.api.quoted.copy(service = TelecomService.Airtime)
        failure(TelecomIssue.InvalidResponse) { f.workflow.review(f.accountId, input(f)) }
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
        f.api.submitFailure = TelecomIssue.Network
        assertNull(f.workflow.submit(f.accountId, review).payment)
        f.api.catalogueFailure = TelecomIssue.Unavailable
        assertEquals(
            listOf(review.request),
            f.workflow.load(f.accountId, TelecomService.Data).requests,
        )
        assertEquals(TelecomIssue.NotFound, f.workflow.recover(f.accountId, review.request).issue)
        assertEquals(1, f.api.submissions.size)
        f.api.submitFailure = null
        f.api.status = TelecomPaymentStatus.Succeeded
        val result = f.workflow.retrySubmission(f.accountId, review.request)
        assertEquals(TelecomService.Data, result.payment?.quote?.service)
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
                    .copy(id = "123e4567-e89b-42d3-a456-426614174023", networkCode = "airtel")
            f.api.catalogue = f.api.catalogue + second
            val vm = vm(f)
            vm.accept(TelecomIntent.Bind(f.accountId, "visit", TelecomService.Data))
            vm.accept(TelecomIntent.SelectProduct(f.api.catalogue.first().id))
            assertNull(vm.uiState.value.selectedProduct)
            vm.accept(TelecomIntent.SelectNetwork("mtn"))
            vm.accept(TelecomIntent.SelectProduct(f.api.catalogue.first().id))
            vm.accept(TelecomIntent.EditRecipient("08012345678"))
            assertNotNull(vm.uiState.value.prepared)
            vm.accept(TelecomIntent.SelectNetwork("mtn"))
            assertNotNull(vm.uiState.value.prepared)
            vm.accept(TelecomIntent.SelectNetwork("airtel"))
            assertNull(vm.uiState.value.selectedProduct)
            assertNull(vm.uiState.value.prepared)
            vm.accept(TelecomIntent.SelectProduct(f.api.catalogue.first().id))
            vm.accept(TelecomIntent.Review)
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
            vm.accept(TelecomIntent.Bind(f.accountId, "same", TelecomService.Data))
            vm.accept(TelecomIntent.SelectNetwork("mtn"))
            vm.accept(TelecomIntent.SelectProduct(f.api.catalogue.first().id))
            vm.accept(TelecomIntent.EditRecipient("08012345678"))
            vm.accept(TelecomIntent.Review)
            vm.accept(TelecomIntent.Bind(f.accountId, "same", TelecomService.Airtime))
            f.api.quoteGate!!.complete(Unit)
            assertEquals(TelecomService.Airtime, vm.uiState.value.service)
            assertEquals(TelecomScreen.Form, vm.uiState.value.screen)
            assertEquals("", vm.uiState.value.recipient)
            assertNull(vm.uiState.value.review)
            assertNull(vm.uiState.value.forScope(f.accountId, "same", TelecomService.Data).context)
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
            val effects = mutableListOf<TelecomEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.collect { effects += it }
            }
            review(vm, f)
            f.api.submitGate = CompletableDeferred()
            vm.accept(TelecomIntent.Confirm)
            vm.accept(TelecomIntent.Confirm)
            assertEquals(1, f.api.submissions.size)
            f.api.submitGate!!.complete(Unit)
            assertTrue(effects.contains(TelecomEffect.RefreshWallet(f.accountId)))
            assertEquals(TelecomPaymentStatus.Pending, vm.uiState.value.progress?.payment?.status)
            vm.accept(TelecomIntent.Bind(null, null))
            vm.accept(TelecomIntent.Bind(f.accountId, "new", TelecomService.Data))
            assertEquals(TelecomScreen.History, vm.uiState.value.screen)
            assertEquals(1, f.api.submissions.size)
            assertEquals(0, f.api.reconciles)
        } finally {
            owners.clear()
        }
    }

    /** Known classifications remain distinct without exposing provider bodies. */
    private suspend fun failure(expected: TelecomIssue, operation: suspend () -> Any?) {
        try {
            operation()
            fail("Expected $expected")
        } catch (failure: TelecomFailure) {
            assertEquals(expected, failure.issue)
        }
    }
}
