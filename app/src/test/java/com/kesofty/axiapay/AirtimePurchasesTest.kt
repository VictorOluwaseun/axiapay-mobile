package com.kesofty.axiapay

import com.kesofty.axiapay.domain.airtime.*
import com.kesofty.axiapay.domain.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Proves ownership, durable consent identity, and honest recovery at the application boundary. */
class AirtimePurchasesTest {
    /** Wallet identity comes from the protected lookup and review alone never spends. */
    @Test
    fun reviewUsesOwnedWalletAndPersistsOnlyOnConfirmation() = runTest {
        val f = AirtimeFixture()
        val context = f.workflow.load(f.accountId)
        val review = f.workflow.review(f.accountId, f.input)
        assertEquals(requireNotNull(context.wallet).id, f.api.quoteWallet)
        assertEquals(f.input, f.api.quotedInput)
        assertTrue(f.api.submissions.isEmpty())
        assertTrue(f.store.records.isEmpty())
        f.api.beforeSubmit = { assertEquals(listOf(review.request), f.store.records) }
        val progress = f.workflow.submit(f.accountId, review)
        assertEquals(AirtimePaymentStatus.Pending, progress.payment?.status)
        assertFalse(requireNotNull(progress.payment).isFinal)
        assertEquals(listOf(review.request), f.api.submissions)
    }

    /** A failed durable commit or stale/unavailable review cannot send a purchase. */
    @Test
    fun unsafeConfirmationSendsNothing() = runTest {
        val f = AirtimeFixture()
        val review = f.workflow.review(f.accountId, f.input)
        f.store.failure = true
        failure(AirtimeIssue.Storage) { f.workflow.submit(f.accountId, review) }
        f.store.failure = false
        failure(AirtimeIssue.ProductUnavailable) {
            f.workflow.submit(
                f.accountId,
                review.copy(quote = review.quote.copy(isSubmissionAvailable = false)),
            )
        }
        f.clock.current = review.quote.expiresAt
        failure(AirtimeIssue.QuoteExpired) { f.workflow.submit(f.accountId, review) }
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Timeout is unconfirmed; restoration reads only and explicit retry reuses quote/key. */
    @Test
    fun uncertaintySurvivesReadOnlyRecoveryAndOriginalIdentityRetry() = runTest {
        val f = AirtimeFixture()
        val review = f.workflow.review(f.accountId, f.input)
        f.api.submitFailure = AirtimeIssue.Network
        val unknown = f.workflow.submit(f.accountId, review)
        assertNull(unknown.payment)
        assertEquals(AirtimeIssue.Network, unknown.issue)
        assertEquals(listOf(review.request), f.workflow.load(f.accountId).requests)
        val missing = f.workflow.recover(f.accountId, review.request)
        assertEquals(AirtimeIssue.NotFound, missing.issue)
        assertEquals(1, f.api.submissions.size)
        f.api.submitFailure = null
        f.api.status = AirtimePaymentStatus.Succeeded
        val result = f.workflow.retrySubmission(f.accountId, review.request)
        assertTrue(requireNotNull(result.payment).isFinal)
        assertEquals(listOf(review.request, review.request), f.api.submissions)
        f.workflow.acknowledge(result)
        assertTrue(f.store.records.single().acknowledged)
        assertEquals(1, f.api.quoteCalls)
    }

    /** Held recovery is explicit and final observations do not dispatch provider work again. */
    @Test
    fun reconcilePreservesAcceptedIdentityAndRequiresAnExplicitCall() = runTest {
        val f = AirtimeFixture()
        val review = f.workflow.review(f.accountId, f.input)
        f.workflow.submit(f.accountId, review)
        f.workflow.recover(f.accountId, review.request)
        assertEquals(0, f.api.reconciles)
        f.api.status = AirtimePaymentStatus.Failed
        val result = f.workflow.reconcile(f.accountId, review.request)
        assertEquals(AirtimePaymentStatus.Failed, result.payment?.status)
        f.workflow.reconcile(f.accountId, review.request)
        assertEquals(1, f.api.reconciles)
        assertEquals(1, f.api.submissions.size)
    }

    /** Neither another account nor another API can resume a saved payment. */
    @Test
    fun accountApiAndRecordScopesAreEnforced() = runTest {
        val f = AirtimeFixture()
        val review = f.workflow.review(f.accountId, f.input)
        failure(AirtimeIssue.SessionChanged) { f.workflow.submit("another-account", review) }
        failure(AirtimeIssue.SessionChanged) {
            f.workflow.submit(
                f.accountId,
                review.copy(
                    request =
                        review.request.copy(
                            owner = review.request.owner.copy(accountId = "another-account"),
                        ),
                ),
            )
        }
        failure(AirtimeIssue.NotFound) { f.workflow.retrySubmission(f.accountId, review.request) }
        f.api.apiIdentity = "https://another.example.test/v1/"
        failure(AirtimeIssue.Configuration) { f.workflow.load(f.accountId) }
        assertTrue(f.api.submissions.isEmpty())
    }

    /** A late quote from a replaced credential cannot publish terms or submit as the new owner. */
    @Test
    fun replacedSessionRejectsLateResponseAndUnauthorizedCleanupIsConditional() = runTest {
        val f = AirtimeFixture()
        f.api.quoteGate = CompletableDeferred()
        val old = requireNotNull(f.sessions.value)
        val newer = old.copy(accessToken = "b".repeat(43))
        val review = async { runCatching { f.workflow.review(f.accountId, f.input) } }
        yield()
        f.sessions.value = newer
        f.api.quoteGate!!.complete(Unit)
        assertEquals(
            AirtimeIssue.SessionChanged,
            (review.await().exceptionOrNull() as AirtimeFailure).issue,
        )
        f.authentication.beforeIdentity = { f.sessions.value = newer }
        f.sessions.value = old
        f.authentication.failure = AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        failure(AirtimeIssue.SignInRequired) { f.workflow.load(f.accountId) }
        assertEquals(newer, f.sessions.value)
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Uncorrelated receipts never become final UI progress or discard recovery records. */
    @Test
    fun mismatchedQuoteTermsRemainUnconfirmedAndPendingCannotBeAcknowledged() = runTest {
        val f = AirtimeFixture()
        val review = f.workflow.review(f.accountId, f.input)
        f.api.quoted =
            f.api.quoted.copy(
                feeMinorUnits = 999.toBigInteger(),
                totalDebitMinorUnits = 10999.toBigInteger(),
            )
        val unknown = f.workflow.submit(f.accountId, review)
        assertNull(unknown.payment)
        assertEquals(AirtimeIssue.InvalidResponse, unknown.issue)
        failure(AirtimeIssue.Unavailable) { f.workflow.acknowledge(unknown) }
        assertFalse(f.store.records.single().acknowledged)
    }

    /** Cancellation after a durable commit keeps recovery but never begins a new POST. */
    @Test
    fun cancellationDuringPersistenceDoesNotSubmitAfterTheUserLeaves() = runTest {
        val f = AirtimeFixture()
        val review = f.workflow.review(f.accountId, f.input)
        f.store.saveGate = CompletableDeferred()
        val action =
            launch(start = CoroutineStart.UNDISPATCHED) { f.workflow.submit(f.accountId, review) }
        action.cancel()
        f.store.saveGate!!.complete(Unit)
        action.join()
        assertTrue(f.api.submissions.isEmpty())
        assertEquals(listOf(review.request), f.store.records)
    }

    /** Receipt/recovery reads remain reachable when the wallet or catalogue cannot load. */
    @Test
    fun savedRequestsRemainRecoverableDuringDisplayOutages() = runTest {
        val f = AirtimeFixture()
        val review = f.workflow.review(f.accountId, f.input)
        f.workflow.submit(f.accountId, review)
        f.wallets.failure =
            com.kesofty.axiapay.domain.wallet.WalletFailure(
                com.kesofty.axiapay.domain.wallet.WalletIssue.Network,
            )
        val context = f.workflow.load(f.accountId)
        assertNull(context.wallet)
        assertEquals(listOf(review.request), context.requests)
        assertNotNull(f.workflow.recover(f.accountId, review.request).payment)
        f.wallets.failure = null
        f.api.catalogueFailure = AirtimeIssue.Unavailable
        assertEquals(listOf(review.request), f.workflow.load(f.accountId).requests)
        assertEquals(1, f.api.submissions.size)
        f.wallets.failure =
            com.kesofty.axiapay.domain.wallet.WalletFailure(
                com.kesofty.axiapay.domain.wallet.WalletIssue.InvalidSession,
            )
        failure(AirtimeIssue.SignInRequired) { f.workflow.load(f.accountId) }
    }

    /** Assertions retain classified failures rather than relying on raw upstream strings. */
    private suspend fun failure(expected: AirtimeIssue, block: suspend () -> Any?) {
        try {
            block()
            fail("Expected $expected")
        } catch (error: AirtimeFailure) {
            assertEquals(expected, error.issue)
        }
    }
}
