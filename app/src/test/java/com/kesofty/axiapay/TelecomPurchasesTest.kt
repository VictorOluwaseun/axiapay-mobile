package com.kesofty.axiapay

import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.telecom.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Proves ownership, durable consent identity, and honest recovery at the application boundary. */
class TelecomPurchasesTest {
    /** Wallet identity comes from the protected lookup and review alone never spends. */
    @Test
    fun reviewUsesOwnedWalletAndPersistsOnlyOnConfirmation() = runTest {
        val f = TelecomFixture()
        val context = f.workflow.load(f.accountId)
        val review = f.workflow.review(f.accountId, f.input)
        assertEquals(requireNotNull(context.wallet).id, f.api.quoteWallet)
        assertEquals(f.input, f.api.quotedInput)
        assertTrue(f.api.submissions.isEmpty())
        assertTrue(f.store.records.isEmpty())
        f.api.beforeSubmit = { assertEquals(listOf(review.request), f.store.records) }
        val progress = f.workflow.submit(f.accountId, review)
        assertEquals(TelecomPaymentStatus.Pending, progress.payment?.status)
        assertFalse(requireNotNull(progress.payment).isFinal)
        assertEquals(listOf(review.request), f.api.submissions)
    }

    /** A failed durable commit or stale/unavailable review cannot send a purchase. */
    @Test
    fun unsafeConfirmationSendsNothing() = runTest {
        val f = TelecomFixture()
        val review = f.workflow.review(f.accountId, f.input)
        f.store.failure = true
        failure(TelecomIssue.Storage) { f.workflow.submit(f.accountId, review) }
        f.store.failure = false
        failure(TelecomIssue.ProductUnavailable) {
            f.workflow.submit(
                f.accountId,
                review.copy(quote = review.quote.copy(isSubmissionAvailable = false)),
            )
        }
        f.clock.current = review.quote.expiresAt
        failure(TelecomIssue.QuoteExpired) { f.workflow.submit(f.accountId, review) }
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Timeout is unconfirmed; restoration reads only and explicit retry reuses quote/key. */
    @Test
    fun uncertaintySurvivesReadOnlyRecoveryAndOriginalIdentityRetry() = runTest {
        val f = TelecomFixture()
        val review = f.workflow.review(f.accountId, f.input)
        f.api.submitFailure = TelecomIssue.Network
        val unknown = f.workflow.submit(f.accountId, review)
        assertNull(unknown.payment)
        assertEquals(TelecomIssue.Network, unknown.issue)
        assertEquals(listOf(review.request), f.workflow.load(f.accountId).requests)
        val missing = f.workflow.recover(f.accountId, review.request)
        assertEquals(TelecomIssue.NotFound, missing.issue)
        assertEquals(1, f.api.submissions.size)
        f.api.submitFailure = null
        f.api.status = TelecomPaymentStatus.Succeeded
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
        val f = TelecomFixture()
        val review = f.workflow.review(f.accountId, f.input)
        f.workflow.submit(f.accountId, review)
        f.workflow.recover(f.accountId, review.request)
        assertEquals(0, f.api.reconciles)
        f.api.status = TelecomPaymentStatus.Failed
        val result = f.workflow.reconcile(f.accountId, review.request)
        assertEquals(TelecomPaymentStatus.Failed, result.payment?.status)
        f.workflow.reconcile(f.accountId, review.request)
        assertEquals(1, f.api.reconciles)
        assertEquals(1, f.api.submissions.size)
    }

    /** Neither another account nor another API can resume a saved payment. */
    @Test
    fun accountApiAndRecordScopesAreEnforced() = runTest {
        val f = TelecomFixture()
        val review = f.workflow.review(f.accountId, f.input)
        failure(TelecomIssue.SessionChanged) { f.workflow.submit("another-account", review) }
        failure(TelecomIssue.SessionChanged) {
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
        failure(TelecomIssue.NotFound) { f.workflow.retrySubmission(f.accountId, review.request) }
        f.api.apiIdentity = "https://another.example.test/v1/"
        failure(TelecomIssue.Configuration) { f.workflow.load(f.accountId) }
        assertTrue(f.api.submissions.isEmpty())
    }

    /** A late quote from a replaced credential cannot publish terms or submit as the new owner. */
    @Test
    fun replacedSessionRejectsLateResponseAndUnauthorizedCleanupIsConditional() = runTest {
        val f = TelecomFixture()
        f.api.quoteGate = CompletableDeferred()
        val old = requireNotNull(f.sessions.value)
        val newer = old.copy(accessToken = "b".repeat(43))
        val review = async { runCatching { f.workflow.review(f.accountId, f.input) } }
        yield()
        f.sessions.value = newer
        f.api.quoteGate!!.complete(Unit)
        assertEquals(
            TelecomIssue.SessionChanged,
            (review.await().exceptionOrNull() as TelecomFailure).issue,
        )
        f.authentication.beforeIdentity = { f.sessions.value = newer }
        f.sessions.value = old
        f.authentication.failure = AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        failure(TelecomIssue.SignInRequired) { f.workflow.load(f.accountId) }
        assertEquals(newer, f.sessions.value)
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Uncorrelated receipts never become final UI progress or discard recovery records. */
    @Test
    fun mismatchedQuoteTermsRemainUnconfirmedAndPendingCannotBeAcknowledged() = runTest {
        val f = TelecomFixture()
        val review = f.workflow.review(f.accountId, f.input)
        f.api.quoted =
            f.api.quoted.copy(
                feeMinorUnits = 999.toBigInteger(),
                totalDebitMinorUnits = 10999.toBigInteger(),
            )
        val unknown = f.workflow.submit(f.accountId, review)
        assertNull(unknown.payment)
        assertEquals(TelecomIssue.InvalidResponse, unknown.issue)
        failure(TelecomIssue.Unavailable) { f.workflow.acknowledge(unknown) }
        assertFalse(f.store.records.single().acknowledged)
    }

    /** Cancellation after a durable commit keeps recovery but never begins a new POST. */
    @Test
    fun cancellationDuringPersistenceDoesNotSubmitAfterTheUserLeaves() = runTest {
        val f = TelecomFixture()
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
        val f = TelecomFixture()
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
        f.api.catalogueFailure = TelecomIssue.Unavailable
        assertEquals(listOf(review.request), f.workflow.load(f.accountId).requests)
        assertEquals(1, f.api.submissions.size)
        f.wallets.failure =
            com.kesofty.axiapay.domain.wallet.WalletFailure(
                com.kesofty.axiapay.domain.wallet.WalletIssue.InvalidSession,
            )
        failure(TelecomIssue.SignInRequired) { f.workflow.load(f.accountId) }
    }

    /** Assertions retain classified failures rather than relying on raw upstream strings. */
    private suspend fun failure(expected: TelecomIssue, block: suspend () -> Any?) {
        try {
            block()
            fail("Expected $expected")
        } catch (error: TelecomFailure) {
            assertEquals(expected, error.issue)
        }
    }
}
