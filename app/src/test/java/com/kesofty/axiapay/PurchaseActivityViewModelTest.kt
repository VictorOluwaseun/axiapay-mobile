package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.presentation.purchases.*
import java.util.Optional
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Verify account clearing and read-only navigation in the actual Activity state owner. */
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseActivityViewModelTest {
    private val owners = ViewModelStore()

    /** Execute state transitions deterministically on the UI dispatcher. */
    @Before
    fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Stop owner jobs and restore the platform dispatcher after each test. */
    @After
    fun stop() {
        owners.clear()
        Dispatchers.resetMain()
    }

    /** Opening, refreshing and closing Activity cannot replay an acknowledged purchase. */
    @Test
    fun activityReadsWithoutSpendingAndClearsOnSignOut() = runTest {
        val fixture = PurchaseFixture()
        val review = fixture.useCases.reviewPurchase(fixture.accountId, fixture.input)
        fixture.api.status = PurchasePaymentStatus.Succeeded
        fixture.useCases.acknowledgePurchase(
            fixture.useCases.submitPurchase(fixture.accountId, review),
        )
        val viewModel = PurchaseActivityViewModel(Optional.of(fixture.useCases))
        owners.put("activity", viewModel)
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, true))
        assertEquals(
            PurchasePaymentStatus.Succeeded,
            viewModel.uiState.value.entries.single().payment?.status,
        )
        viewModel.accept(PurchaseActivityIntent.Refresh)
        assertEquals(1, fixture.api.submissions.size)
        assertEquals(0, fixture.api.reconciles)
        assertTrue(viewModel.uiState.value.forAccount("another-account").entries.isEmpty())
        viewModel.accept(PurchaseActivityIntent.Bind(null, false))
        assertTrue(viewModel.uiState.value.entries.isEmpty())
        val reads = fixture.api.gets
        viewModel.accept(PurchaseActivityIntent.Refresh)
        assertEquals(reads, fixture.api.gets)
    }

    /** Demo composition supplies no live operation or account history. */
    @Test
    fun absentLiveFeatureRemainsInactive() = runTest {
        val viewModel = PurchaseActivityViewModel(Optional.empty())
        owners.put("demo", viewModel)
        viewModel.accept(PurchaseActivityIntent.Bind("account", true))
        assertNull(viewModel.uiState.value.accountId)
        assertTrue(viewModel.uiState.value.entries.isEmpty())
    }

    /**
     * Home and Activity share one read, retain same-account snapshots and refresh after a sheet
     * closes.
     */
    @Test
    fun sharedHistoryRefreshesAfterPurchaseAndRetainsConfirmedRowsDuringFailure() = runTest {
        val fixture = PurchaseFixture()
        val viewModel = PurchaseActivityViewModel(Optional.of(fixture.useCases))
        owners.put("shared", viewModel)
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, true))
        assertTrue(viewModel.uiState.value.entries.isEmpty())
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, false))
        val review = fixture.useCases.reviewPurchase(fixture.accountId, fixture.input)
        fixture.api.status = PurchasePaymentStatus.Succeeded
        fixture.useCases.acknowledgePurchase(
            fixture.useCases.submitPurchase(fixture.accountId, review),
        )
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, true))
        val entries = viewModel.uiState.value.entries
        assertEquals(review.request.requestKey, entries.single().request.requestKey)
        val reads = fixture.api.gets
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, true))
        assertEquals(reads, fixture.api.gets)
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, false))
        assertEquals(entries, viewModel.uiState.value.entries)
        fixture.api.historyFailure = PurchaseIssue.Unavailable
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, true))
        assertEquals(entries, viewModel.uiState.value.entries)
        assertEquals(PurchaseIssue.Unavailable, viewModel.uiState.value.issue)
        fixture.api.historyFailure = null
        viewModel.accept(PurchaseActivityIntent.Refresh)
        assertNull(viewModel.uiState.value.issue)
        assertEquals(1, fixture.api.submissions.size)
        assertEquals(0, fixture.api.reconciles)
    }

    /**
     * An in-flight read cannot leak into a different account, even when its adapter ignores
     * cancellation.
     */
    @Test
    fun lateHistoryCannotRepopulateSignedOutHomeAndRefreshesDoNotOverlap() = runTest {
        val fixture = PurchaseFixture()
        val review = fixture.useCases.reviewPurchase(fixture.accountId, fixture.input)
        fixture.api.status = PurchasePaymentStatus.Succeeded
        fixture.useCases.submitPurchase(fixture.accountId, review)
        val gate = CompletableDeferred<Unit>()
        fixture.api.historyGate = gate
        val viewModel = PurchaseActivityViewModel(Optional.of(fixture.useCases))
        owners.put("late-history", viewModel)
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, true))
        assertTrue(viewModel.uiState.value.loading)
        val reads = fixture.api.gets
        viewModel.accept(PurchaseActivityIntent.Refresh)
        assertEquals(reads, fixture.api.gets)
        viewModel.accept(PurchaseActivityIntent.Bind(null, false))
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.accountId)
        assertTrue(viewModel.uiState.value.entries.isEmpty())
        assertFalse(viewModel.uiState.value.loading)
        assertEquals(1, fixture.api.submissions.size)
        assertEquals(0, fixture.api.reconciles)
    }

    /**
     * Receipt selection is account-scoped, follows fresh observations and never executes payment
     * work.
     */
    @Test
    fun receiptsFollowRefreshAndDismissWithoutSpending() = runTest {
        val fixture = PurchaseFixture()
        val review = fixture.useCases.reviewPurchase(fixture.accountId, fixture.input)
        fixture.api.status = PurchasePaymentStatus.Succeeded
        fixture.useCases.acknowledgePurchase(
            fixture.useCases.submitPurchase(fixture.accountId, review),
        )
        val viewModel = PurchaseActivityViewModel(Optional.of(fixture.useCases))
        owners.put("receipt", viewModel)
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, true))
        val entry = viewModel.uiState.value.entries.single()
        val reads = fixture.api.gets
        viewModel.accept(PurchaseActivityIntent.OpenReceipt("unknown-request"))
        assertNull(viewModel.uiState.value.selectedEntry)
        viewModel.accept(PurchaseActivityIntent.OpenReceipt(entry.request.requestKey))
        assertEquals(entry, viewModel.uiState.value.selectedEntry)
        assertEquals(reads, fixture.api.gets)
        assertNull(viewModel.uiState.value.forAccount("other-account").selectedEntry)
        viewModel.accept(PurchaseActivityIntent.Refresh)
        fixture.api.historyFailure = PurchaseIssue.Network
        viewModel.accept(PurchaseActivityIntent.Refresh)
        assertNotNull(viewModel.uiState.value.selectedEntry)
        assertEquals(PurchaseIssue.Network, viewModel.uiState.value.issue)
        viewModel.accept(PurchaseActivityIntent.CloseReceipt)
        assertNull(viewModel.uiState.value.selectedEntry)
        viewModel.accept(PurchaseActivityIntent.OpenReceipt(entry.request.requestKey))
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, false))
        assertNull(viewModel.uiState.value.selectedEntry)
        viewModel.accept(PurchaseActivityIntent.OpenReceipt(entry.request.requestKey))
        assertNull(viewModel.uiState.value.selectedEntry)
        fixture.api.historyFailure = null
        viewModel.accept(PurchaseActivityIntent.Bind(fixture.accountId, true))
        viewModel.accept(PurchaseActivityIntent.OpenReceipt(entry.request.requestKey))
        viewModel.accept(PurchaseActivityIntent.Bind(null, false))
        assertNull(viewModel.uiState.value.selectedEntry)
        assertEquals(1, fixture.api.submissions.size)
        assertEquals(0, fixture.api.reconciles)
    }
}
