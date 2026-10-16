package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.telecom.PrepareTelecom
import com.kesofty.axiapay.application.telecom.TelecomPurchases
import com.kesofty.axiapay.domain.telecom.*
import com.kesofty.axiapay.presentation.telecom.*
import java.util.Optional
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Regression coverage for request-owned quote fallback and expiry during failed confirmation. */
@OptIn(ExperimentalCoroutinesApi::class)
class TelecomRecoveryRegressionTest {
    private val owners = ViewModelStore()

    /** Control presentation work while retaining actual workflows and reducers. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** No request or expiry ticker survives its test owner. */
    @After
    fun tearDown() {
        owners.clear()
        Dispatchers.resetMain()
    }

    /** Missing saved terms cannot borrow a previously viewed payment, for either phone service. */
    @Test
    fun missingSavedQuoteNeverBorrowsAnotherPaymentsTerms() = runTest {
        try {
            for (service in listOf(TelecomService.Airtime, TelecomService.Data)) {
                val f = fixture(service)
                val requestA =
                    f.workflow.review(f.accountId, f.input.copy(service = service)).request
                val requestB =
                    requestA.copy(
                        requestKey = "123e4567-e89b-42d3-a456-426614174080",
                        quoteId = "123e4567-e89b-42d3-a456-426614174081",
                    )
                f.store.records = listOf(requestA, requestB)
                val vm = vm(f, missingQuotes(f))
                vm.accept(TelecomIntent.Bind(f.accountId, "visit", service))
                f.api.observed = f.api.payment(requestA)
                vm.accept(TelecomIntent.CheckSaved(requestA.requestKey))
                assertEquals(requestA.quoteId, vm.uiState.value.progress?.quote?.id)
                vm.accept(TelecomIntent.History)
                f.api.observed = null
                vm.accept(TelecomIntent.CheckSaved(requestB.requestKey))
                assertEquals(requestB, vm.uiState.value.progress?.request)
                assertNull(vm.uiState.value.progress?.quote)
                assertEquals(TelecomIssue.NotFound, vm.uiState.value.progress?.issue)
                assertTrue(f.api.submissions.isEmpty())
            }
        } finally {
            owners.clear()
        }
    }

    /** An unsubmitted review is equally private to its own request when opening saved history. */
    @Test
    fun missingSavedQuoteNeverBorrowsAnotherReviewsTerms() = runTest {
        try {
            val f = TelecomFixture()
            val saved =
                f.workflow
                    .review(f.accountId, f.input)
                    .request
                    .copy(
                        requestKey = "123e4567-e89b-42d3-a456-426614174080",
                        quoteId = "123e4567-e89b-42d3-a456-426614174081",
                    )
            f.store.records = listOf(saved)
            val vm = vm(f, missingQuotes(f))
            vm.accept(TelecomIntent.Bind(f.accountId, "visit"))
            vm.accept(TelecomIntent.NewPurchase)
            review(vm, f)
            vm.accept(TelecomIntent.History)
            vm.accept(TelecomIntent.CheckSaved(saved.requestKey))
            assertEquals(saved, vm.uiState.value.progress?.request)
            assertNull(vm.uiState.value.progress?.quote)
            assertTrue(f.api.submissions.isEmpty())
        } finally {
            owners.clear()
        }
    }

    /**
     * Retrying the same uncertain request keeps its already verified display terms and identity.
     */
    @Test
    fun uncertainRetryRetainsOnlyTheSameRequestsVerifiedQuote() = runTest {
        try {
            val f = TelecomFixture()
            val vm = vm(f)
            vm.accept(TelecomIntent.Bind(f.accountId, "visit"))
            review(vm, f)
            val reviewed = requireNotNull(vm.uiState.value.review)
            f.api.submitFailure = TelecomIssue.Network
            vm.accept(TelecomIntent.Confirm)
            vm.accept(TelecomIntent.RetrySubmission)
            assertEquals(reviewed.quote, vm.uiState.value.progress?.quote)
            assertEquals(reviewed.request, vm.uiState.value.progress?.request)
            assertNull(vm.uiState.value.progress?.payment)
            assertEquals(listOf(reviewed.request, reviewed.request), f.api.submissions)
            assertEquals(1, f.api.quoteCalls)
        } finally {
            owners.clear()
        }
    }

    /** A failed storage commit keeps the retained review's expiry live without sending a POST. */
    @Test
    fun storageFailureKeepsExpiryTrackingUntilConfirmationIsDisabled() = runTest {
        try {
            val f = TelecomFixture()
            val vm = vm(f)
            vm.accept(TelecomIntent.Bind(f.accountId, "visit"))
            review(vm, f)
            f.store.failure = true
            vm.accept(TelecomIntent.Confirm)
            assertEquals(TelecomIssue.Storage, vm.uiState.value.issue)
            assertEquals(TelecomScreen.Review, vm.uiState.value.screen)
            expire(f)
            assertEquals(0L, vm.uiState.value.secondsRemaining)
            f.store.failure = false
            vm.accept(TelecomIntent.Confirm)
            assertTrue(f.api.submissions.isEmpty())
            assertTrue(f.store.records.isEmpty())
        } finally {
            owners.clear()
        }
    }

    /**
     * A review can expire during storage IO; returning failure must not restart an enabled timer.
     */
    @Test
    fun expiryContinuesWhileConfirmationIsSuspendedAndAfterFailure() = runTest {
        try {
            val f = TelecomFixture()
            val gate = CompletableDeferred<Unit>()
            val store =
                object : TelecomRequestStore by f.store {
                    /**
                     * Model a pending fsync that reports failure without permitting the purchase.
                     */
                    override suspend fun save(request: TelecomRequest) {
                        gate.await()
                        throw TelecomFailure(TelecomIssue.Storage)
                    }
                }
            val workflow = workflow(f, f.api, store)
            val vm = vm(f, workflow)
            vm.accept(TelecomIntent.Bind(f.accountId, "visit"))
            review(vm, f)
            vm.accept(TelecomIntent.Confirm)
            assertTrue(vm.uiState.value.isProcessing)
            expire(f)
            assertEquals(0L, vm.uiState.value.secondsRemaining)
            gate.complete(Unit)
            assertFalse(vm.uiState.value.isProcessing)
            assertEquals(TelecomScreen.Review, vm.uiState.value.screen)
            assertEquals(0L, vm.uiState.value.secondsRemaining)
            assertTrue(f.api.submissions.isEmpty())
        } finally {
            owners.clear()
        }
    }

    /**
     * Build internally valid service fixtures without moving response validation into workflows.
     */
    private fun fixture(service: TelecomService) =
        TelecomFixture().apply {
            if (service == TelecomService.Data) {
                val price = 10000.toBigInteger()
                api.catalogue =
                    listOf(
                        api.product.copy(
                            service = service,
                            fixedMinorUnits = price,
                            minimumMinorUnits = price,
                            maximumMinorUnits = price,
                            allowanceLabel = "1 GB",
                            validitySeconds = 86400,
                        ),
                    )
                api.quoted =
                    api.quoted.copy(
                        service = service,
                        allowanceLabel = "1 GB",
                        validitySeconds = 86400,
                    )
            }
        }

    /** Missing quote responses are distinct from a network failure and may produce null terms. */
    private fun missingQuotes(f: TelecomFixture): TelecomPurchases {
        val api =
            object : TelecomRepository by f.api {
                /** Reproduce the owned-quote 404 contract without a fake successful quote. */
                override suspend fun getQuote(accessToken: String, quoteId: String): TelecomQuote {
                    throw TelecomFailure(TelecomIssue.NotFound)
                }
            }
        return workflow(f, api, f.store)
    }

    /** Compose an actual application operation with isolated ports for the failure under test. */
    private fun workflow(f: TelecomFixture, api: TelecomRepository, store: TelecomRequestStore) =
        TelecomPurchases(f.authentication, f.sessions, f.wallets, api, store, f.clock) {
            "123e4567-e89b-42d3-a456-426614174005"
        }

    /** Lifecycle ownership makes cancellation and expiry behavior match production. */
    private fun vm(f: TelecomFixture, workflow: TelecomPurchases = f.workflow) =
        TelecomViewModel(Optional.of(workflow), PrepareTelecom(), f.clock).also {
            owners.put("review", it)
        }

    /** Create an immutable airtime review through actual intents without submitting anything. */
    private fun review(vm: TelecomViewModel, f: TelecomFixture) {
        vm.accept(TelecomIntent.SelectProduct(f.api.product.id))
        vm.accept(TelecomIntent.EditRecipient("08012345678"))
        vm.accept(TelecomIntent.EditAmount("100"))
        vm.accept(TelecomIntent.Review)
        assertEquals(TelecomScreen.Review, vm.uiState.value.screen)
    }

    /** Advance both wall time and the presentation scheduler past the quoted deadline. */
    private fun TestScope.expire(f: TelecomFixture) {
        f.clock.current = f.api.quoted.expiresAt
        advanceTimeBy(1001)
        runCurrent()
    }
}
