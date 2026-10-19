package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.purchases.*
import com.kesofty.axiapay.domain.ElectricityMeterType
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.presentation.purchases.*
import java.util.Optional
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Electricity owns verified meter terms while sharing protected consent and durable recovery. */
@OptIn(ExperimentalCoroutinesApi::class)
class ElectricityPurchasesTest {
    private val owners = ViewModelStore()

    /** Execute reducers deterministically without an Android runtime. */
    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Release every lifecycle job and countdown. */
    @After
    fun close() {
        owners.clear()
        Dispatchers.resetMain()
    }

    /**
     * Model API catalogue and verification separately from the existing demo provider catalogue.
     */
    private fun fixture(type: ElectricityMeterType = ElectricityMeterType.Prepaid) =
        PurchaseFixture().apply {
            val product =
                api.product.copy(
                    providerCode = "ikeja-electric",
                    displayName = "Ikeja electricity",
                    service = PurchaseService.Electricity,
                    meterType = type,
                )
            api.catalogue = listOf(product)
            api.meter =
                MeterValidation(
                    "123e4567-e89b-42d3-a456-426614174030",
                    product.id,
                    product.providerCode,
                    type,
                    "00123456789",
                    "Test customer",
                    "Test address",
                    now,
                    now.plusSeconds(120),
                )
            api.quoted =
                api.quoted.copy(
                    service = product.service,
                    providerCode = product.providerCode,
                    displayName = product.displayName,
                    recipientIdentifier = api.meter!!.accountNumber,
                    meterValidation = api.meter,
                    contactPhoneNumber = "+2348012345678",
                )
        }

    /** Prepare normalized contact and exact meter/amount once before requesting a quote. */
    private suspend fun input(f: PurchaseFixture): PurchaseQuoteInput {
        val product = f.api.catalogue.first()
        val verified = f.workflow.verifyMeter(f.accountId, product, "00123456789")
        return requireNotNull(
            PreparePurchase()(product, "00123456789", "100", verified, "08012345678").input,
        )
    }

    /** Create a retained reducer with the real application workflow. */
    private fun vm(f: PurchaseFixture) =
        PurchaseViewModel(Optional.of(f.workflow), PreparePurchase(), f.clock).also {
            it.accept(
                PurchaseIntent.UpdateFeatures(
                    com.kesofty.axiapay.domain.config.PaymentFeatures.AllEnabled,
                ),
            )
            owners.put("electricity", it)
        }

    /**
     * Fill explicit catalogue/recipient/contact choices without starting verification or payment.
     */
    private fun form(vm: PurchaseViewModel, f: PurchaseFixture) {
        vm.accept(PurchaseIntent.Bind(f.accountId, "visit", PurchaseService.Electricity))
        vm.accept(PurchaseIntent.SelectProvider("ikeja-electric"))
        vm.accept(PurchaseIntent.SelectProduct(f.api.catalogue.first().id))
        vm.accept(PurchaseIntent.EditRecipient("00123456789"))
        vm.accept(PurchaseIntent.EditContactPhone("08012345678"))
        vm.accept(PurchaseIntent.EditAmount("100"))
    }

    /**
     * Quote creation uses the current owner's wallet and a matching verified meter in both modes.
     */
    @Test
    fun prepaidAndPostpaidUseOwnedWalletAndExactMeter() = runTest {
        for (type in ElectricityMeterType.entries) {
            val f = fixture(type)
            val review = f.workflow.review(f.accountId, input(f))
            assertEquals(f.wallets.wallet.id, f.api.quoteWallet)
            assertEquals("00123456789", review.quote.recipientIdentifier)
            assertEquals(type, review.quote.meterValidation?.meterType)
            assertEquals("+2348012345678", f.api.quotedInput?.contactPhoneNumber)
            assertEquals(1, f.api.verifyCalls)
            assertTrue(f.api.submissions.isEmpty())
        }
    }

    /** Verification capabilities cannot cross owner/API boundaries or their server expiry. */
    @Test
    fun missingForeignAndExpiredVerificationCannotRequestQuote() = runTest {
        val f = fixture()
        val valid = input(f)
        failure(PurchaseIssue.MeterVerificationRequired) {
            f.workflow.review(f.accountId, valid.copy(verifiedMeter = null))
        }
        val foreign =
            valid.verifiedMeter!!.copy(owner = valid.verifiedMeter.owner.copy(accountId = "other"))
        failure(PurchaseIssue.SessionChanged) {
            f.workflow.review(f.accountId, valid.copy(verifiedMeter = foreign))
        }
        val otherApi =
            valid.verifiedMeter.copy(
                owner = valid.verifiedMeter.owner.copy(apiIdentity = "https://other.test/"),
            )
        failure(PurchaseIssue.SessionChanged) {
            f.workflow.review(f.accountId, valid.copy(verifiedMeter = otherApi))
        }
        f.clock.current = f.now.plusSeconds(121)
        failure(PurchaseIssue.MeterVerificationExpired) { f.workflow.review(f.accountId, valid) }
        assertEquals(0, f.api.quoteCalls)
    }

    /** The workflow rejects wrong product, biller, mode and recipient verification evidence. */
    @Test
    fun verificationAndQuoteMustCorrelateWithSelectedMeter() = runTest {
        val f = fixture()
        val original = f.api.meter!!
        for (wrong in
            listOf(
                original.copy(productId = "other"),
                original.copy(billerCode = "other"),
                original.copy(meterType = ElectricityMeterType.Postpaid),
                original.copy(accountNumber = "different"),
            )) {
            f.api.meter = wrong
            failure(PurchaseIssue.InvalidResponse) {
                f.workflow.verifyMeter(f.accountId, f.api.catalogue.first(), original.accountNumber)
            }
        }
        f.api.meter = original
        val prepared = input(f)
        f.api.quoted =
            f.api.quoted.copy(meterValidation = original.copy(customerName = "different"))
        failure(PurchaseIssue.InvalidResponse) { f.workflow.review(f.accountId, prepared) }
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Meter edits and selected product/mode/provider changes discard stale verification. */
    @Test
    fun editingMeterOrProductChoicesRequiresFreshVerification() = runTest {
        val f = fixture()
        val second = f.api.catalogue.first().copy(id = "second", providerCode = "eko-electric")
        f.api.catalogue += second
        val vm = vm(f)
        form(vm, f)
        assertNull(vm.uiState.value.prepared)
        vm.accept(PurchaseIntent.VerifyMeter)
        assertNotNull(vm.uiState.value.prepared)
        vm.accept(PurchaseIntent.EditAmount("101"))
        assertNotNull(vm.uiState.value.verifiedMeter)
        vm.accept(PurchaseIntent.EditRecipient("00123456780"))
        assertNull(vm.uiState.value.prepared)
        assertNull(vm.uiState.value.verifiedMeter)
        vm.accept(PurchaseIntent.Review)
        assertEquals(0, f.api.quoteCalls)
        vm.accept(PurchaseIntent.EditRecipient("00123456789"))
        vm.accept(PurchaseIntent.VerifyMeter)
        vm.accept(PurchaseIntent.SelectMeterType(ElectricityMeterType.Postpaid))
        assertNull(vm.uiState.value.selectedProduct)
        assertNull(vm.uiState.value.verifiedMeter)
        vm.accept(PurchaseIntent.SelectMeterType(ElectricityMeterType.Prepaid))
        vm.accept(PurchaseIntent.SelectProduct(f.api.catalogue.first().id))
        vm.accept(PurchaseIntent.VerifyMeter)
        vm.accept(PurchaseIntent.SelectProvider("eko-electric"))
        assertNull(vm.uiState.value.verifiedMeter)
        assertNull(vm.uiState.value.selectedProduct)
    }

    /** Invalid/unconfirmed verification never creates a quote, journal entry or purchase. */
    @Test
    fun failedVerificationAndExpiryRemainActionableWithoutSpending() = runTest {
        val f = fixture()
        val vm = vm(f)
        form(vm, f)
        for (issue in listOf(PurchaseIssue.InvalidMeter, PurchaseIssue.MeterUnconfirmed)) {
            f.api.verifyFailure = issue
            vm.accept(PurchaseIntent.VerifyMeter)
            assertEquals(issue, vm.uiState.value.issue)
            assertNull(vm.uiState.value.verifiedMeter)
        }
        f.api.verifyFailure = null
        vm.accept(PurchaseIntent.VerifyMeter)
        f.clock.current = f.now.plusSeconds(121)
        vm.accept(PurchaseIntent.Review)
        assertEquals(PurchaseIssue.MeterVerificationExpired, vm.uiState.value.issue)
        assertEquals(0, f.api.quoteCalls)
        assertTrue(f.store.records.isEmpty())
        assertTrue(f.api.submissions.isEmpty())
    }

    /**
     * An explicit re-verification cannot leave earlier verified terms usable after a failed
     * response.
     */
    @Test
    fun failedReverificationDiscardsPreviousCapability() = runTest {
        val f = fixture()
        val vm = vm(f)
        form(vm, f)
        vm.accept(PurchaseIntent.VerifyMeter)
        assertNotNull(vm.uiState.value.prepared)
        f.api.verifyGate = CompletableDeferred()
        f.api.verifyFailure = PurchaseIssue.MeterUnconfirmed
        vm.accept(PurchaseIntent.VerifyMeter)
        assertNull(vm.uiState.value.verifiedMeter)
        assertNull(vm.uiState.value.prepared)
        f.api.verifyGate!!.complete(Unit)
        assertEquals(PurchaseIssue.MeterUnconfirmed, vm.uiState.value.issue)
        assertNull(vm.uiState.value.verifiedMeter)
        vm.accept(PurchaseIntent.Review)
        assertEquals(0, f.api.quoteCalls)
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Cancellation-resistant verification cannot leak a meter into another account/visit. */
    @Test
    fun lateVerificationIsDiscardedAfterScopeChange() = runTest {
        val f = fixture()
        val vm = vm(f)
        form(vm, f)
        f.api.verifyGate = CompletableDeferred()
        vm.accept(PurchaseIntent.VerifyMeter)
        vm.accept(PurchaseIntent.VerifyMeter)
        assertEquals(1, f.api.verifyCalls)
        vm.accept(PurchaseIntent.Bind(null, "next", PurchaseService.Electricity))
        f.api.verifyGate!!.complete(Unit)
        assertNull(vm.uiState.value.verifiedMeter)
        assertEquals("", vm.uiState.value.recipient)
        assertEquals(PurchaseScreen.SignIn, vm.uiState.value.screen)
    }

    /** Successful settlement with delayed tokens keeps its reference and never retries purchase. */
    @Test
    fun delayedTokenRecoveryUsesExistingPaymentAndCannotBeAcknowledgedEarly() = runTest {
        val f = fixture()
        val vm = vm(f)
        form(vm, f)
        vm.accept(PurchaseIntent.VerifyMeter)
        vm.accept(PurchaseIntent.Review)
        f.api.status = PurchasePaymentStatus.Succeeded
        vm.accept(PurchaseIntent.Confirm)
        assertTrue(vm.uiState.value.progress!!.payment!!.needsReceipt)
        vm.accept(PurchaseIntent.Done)
        vm.accept(PurchaseIntent.RetrySubmission)
        assertFalse(f.store.records.single().acknowledged)
        assertEquals(1, f.api.submissions.size)
        f.api.receipt =
            ElectricityReceipt(
                ElectricityMeterType.Prepaid,
                "receipt",
                listOf(
                    ElectricityToken("Key change", "00000000000000000001"),
                    ElectricityToken("Recharge", "00000000000000000002"),
                ),
                "12.375",
            )
        vm.accept(PurchaseIntent.Reconcile)
        assertEquals(1, f.api.reconciles)
        assertEquals(1, f.api.submissions.size)
        assertEquals(1, f.api.quoteCalls)
        assertEquals(
            "00000000000000000001",
            vm.uiState.value.progress!!.payment!!.electricityReceipt!!.tokens.first().value,
        )
        vm.accept(PurchaseIntent.Done)
        assertTrue(f.store.records.single().acknowledged)
    }

    /** Unknown responses keep the original durable identity and restoration performs reads only. */
    @Test
    fun uncertaintyStorageAndRestorationPreserveElectricityRequest() = runTest {
        val f = fixture()
        val vm = vm(f)
        form(vm, f)
        vm.accept(PurchaseIntent.VerifyMeter)
        vm.accept(PurchaseIntent.Review)
        f.store.failure = true
        vm.accept(PurchaseIntent.Confirm)
        assertEquals(PurchaseIssue.Storage, vm.uiState.value.issue)
        assertTrue(f.api.submissions.isEmpty())
        f.store.failure = false
        f.api.submitFailure = PurchaseIssue.Network
        vm.accept(PurchaseIntent.Confirm)
        assertNull(vm.uiState.value.progress!!.payment)
        val saved = f.store.records.single()
        assertEquals(PurchaseService.Electricity, saved.service)
        vm.accept(PurchaseIntent.Bind(null, null))
        vm.accept(PurchaseIntent.Bind(f.accountId, "reopened", PurchaseService.Electricity))
        assertEquals(PurchaseScreen.History, vm.uiState.value.screen)
        assertEquals(1, f.api.submissions.size)
        assertEquals(0, f.api.reconciles)
        assertEquals(listOf(saved), vm.uiState.value.context!!.requests)
    }

    /**
     * Boundary preparation preserves meter syntax and rejects invalid contact/exact decimal input.
     */
    @Test
    fun preparationPreservesLeadingZerosAndRequiresVerifiedMatchingProduct() = runTest {
        val f = fixture()
        val input = input(f)
        val product = f.api.catalogue.first()
        assertEquals("00123456789", input.recipientIdentifier)
        assertFalse(hasMeterSyntax(" 001234"))
        assertFalse(hasMeterSyntax("001\n234"))
        assertEquals(
            PurchaseIssue.InvalidRecipient,
            PreparePurchase()(product, input.recipientIdentifier, "100", input.verifiedMeter, "bad")
                .issue,
        )
        assertEquals(
            PurchaseIssue.InvalidAmount,
            PreparePurchase()(
                    product,
                    input.recipientIdentifier,
                    "100.001",
                    input.verifiedMeter,
                    "08012345678",
                )
                .issue,
        )
        assertNull(
            PreparePurchase()(product, "different", "100", input.verifiedMeter, "08012345678")
                .input,
        )
    }

    /** Assert safe classification while preserving coroutine cancellation and failure context. */
    private suspend fun failure(expected: PurchaseIssue, action: suspend () -> Any?) {
        try {
            action()
            fail("Expected $expected")
        } catch (failure: PurchaseFailure) {
            assertEquals(expected, failure.issue)
        }
    }
}
