package com.kesofty.axiapay

import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.purchases.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Proves ownership, durable consent identity, and honest recovery at the application boundary. */
class WalletPurchasesTest {
    /**
     * Rotated access retains the same consent identity and does not invalidate an in-flight review.
     */
    @Test
    fun rotationDuringPurchasePreparationKeepsTheOriginalRequest() = runTest {
        val fixture = PurchaseFixture()
        val initial =
            fixture.sessions.value!!.copy(
                accountId = fixture.accountId,
                expiresAt = fixture.now.minusSeconds(1),
                refreshCredentials =
                    RefreshCredentials(
                        "r".repeat(43),
                        fixture.now.plusSeconds(2592000),
                        "946a98d5-246f-41d5-93c6-cf052fb10769",
                    ),
            )
        fixture.sessions.value = initial
        fixture.authentication.beforeIdentity = {
            fixture.sessions.value =
                initial.copy(
                    accessToken = "b".repeat(43),
                    expiresAt = fixture.now.plusSeconds(900),
                    refreshCredentials = initial.refreshCredentials!!.copy(token = "s".repeat(43)),
                )
        }
        val review = fixture.useCases.reviewPurchase(fixture.accountId, fixture.input)
        val result = fixture.useCases.submitPurchase(fixture.accountId, review)
        assertEquals(review.request, result.request)
        assertEquals(listOf(review.request), fixture.api.submissions)
    }

    /** Wallet identity comes from the protected lookup and review alone never spends. */
    @Test
    fun reviewUsesOwnedWalletAndPersistsOnlyOnConfirmation() = runTest {
        val f = PurchaseFixture()
        val context = f.useCases.refreshPurchaseContext(f.accountId)
        val review = f.useCases.reviewPurchase(f.accountId, f.input)
        assertEquals(requireNotNull(context.wallet).id, f.api.quoteWallet)
        assertEquals(f.input, f.api.quotedInput)
        assertTrue(f.api.submissions.isEmpty())
        assertTrue(f.store.records.isEmpty())
        f.api.beforeSubmit = { assertEquals(listOf(review.request), f.store.records) }
        val progress = f.useCases.submitPurchase(f.accountId, review)
        assertEquals(PurchasePaymentStatus.Pending, progress.payment?.status)
        assertFalse(requireNotNull(progress.payment).isFinal)
        assertEquals(listOf(review.request), f.api.submissions)
    }

    /**
     * Server terms for another selection cannot create a review or durable spending instruction.
     */
    @Test
    fun reviewRejectsQuotesForADifferentPurchase() = runTest {
        val f = PurchaseFixture()
        val quote = f.api.quoted
        val mismatches =
            listOf(
                quote.copy(walletId = "another-wallet"),
                quote.copy(productId = "another-product"),
                quote.copy(recipientIdentifier = "+2348098765432"),
                quote.copy(currency = "USD"),
                quote.copy(principalMinorUnits = quote.principalMinorUnits + 1.toBigInteger()),
            )
        for (differentQuote in mismatches) {
            f.api.quoted = differentQuote
            failure(PurchaseIssue.InvalidResponse) {
                f.useCases.reviewPurchase(f.accountId, f.input)
            }
        }
        assertTrue(f.store.records.isEmpty())
        assertTrue(f.api.submissions.isEmpty())
    }

    /** A failed durable commit or stale/unavailable review cannot send a purchase. */
    @Test
    fun unsafeConfirmationSendsNothing() = runTest {
        val f = PurchaseFixture()
        val review = f.useCases.reviewPurchase(f.accountId, f.input)
        f.store.failure = true
        failure(PurchaseIssue.Storage) { f.useCases.submitPurchase(f.accountId, review) }
        f.store.failure = false
        failure(PurchaseIssue.ProductUnavailable) {
            f.useCases.submitPurchase(
                f.accountId,
                review.copy(quote = review.quote.copy(isSubmissionAvailable = false)),
            )
        }
        f.clock.current = review.quote.expiresAt
        failure(PurchaseIssue.QuoteExpired) { f.useCases.submitPurchase(f.accountId, review) }
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Timeout is unconfirmed; restoration reads only and explicit retry reuses quote/key. */
    @Test
    fun uncertaintySurvivesReadOnlyRecoveryAndOriginalIdentityRetry() = runTest {
        val f = PurchaseFixture()
        val review = f.useCases.reviewPurchase(f.accountId, f.input)
        f.api.submitFailure = PurchaseIssue.Network
        val unknown = f.useCases.submitPurchase(f.accountId, review)
        assertNull(unknown.payment)
        assertEquals(PurchaseIssue.Network, unknown.issue)
        assertEquals(
            listOf(review.request),
            f.useCases.refreshPurchaseContext(f.accountId).requests,
        )
        val missing = f.useCases.payments.recover(f.accountId, review.request)
        assertEquals(PurchaseIssue.NotFound, missing.issue)
        assertEquals(1, f.api.submissions.size)
        f.api.submitFailure = null
        f.api.status = PurchasePaymentStatus.Succeeded
        val result = f.useCases.submitPurchase.retry(f.accountId, review.request)
        assertTrue(requireNotNull(result.payment).isFinal)
        assertEquals(listOf(review.request, review.request), f.api.submissions)
        f.useCases.payments.acknowledge(result)
        assertTrue(f.store.records.single().acknowledged)
        assertEquals(1, f.api.quoteCalls)
    }

    /** Held recovery is explicit and final observations do not dispatch provider work again. */
    @Test
    fun reconcilePreservesAcceptedIdentityAndRequiresAnExplicitCall() = runTest {
        val f = PurchaseFixture()
        val review = f.useCases.reviewPurchase(f.accountId, f.input)
        f.useCases.submitPurchase(f.accountId, review)
        f.useCases.payments.recover(f.accountId, review.request)
        assertEquals(0, f.api.reconciles)
        f.api.status = PurchasePaymentStatus.Failed
        val result = f.useCases.payments.reconcile(f.accountId, review.request)
        assertEquals(PurchasePaymentStatus.Failed, result.payment?.status)
        f.useCases.payments.reconcile(f.accountId, review.request)
        assertEquals(1, f.api.reconciles)
        assertEquals(1, f.api.submissions.size)
    }

    /** Neither another account nor another API can resume a saved payment. */
    @Test
    fun accountApiAndRecordScopesAreEnforced() = runTest {
        val f = PurchaseFixture()
        val review = f.useCases.reviewPurchase(f.accountId, f.input)
        failure(PurchaseIssue.SessionChanged) {
            f.useCases.submitPurchase("another-account", review)
        }
        failure(PurchaseIssue.SessionChanged) {
            f.useCases.submitPurchase(
                f.accountId,
                review.copy(
                    request =
                        review.request.copy(
                            owner = review.request.owner.copy(accountId = "another-account"),
                        ),
                ),
            )
        }
        failure(PurchaseIssue.NotFound) {
            f.useCases.submitPurchase.retry(f.accountId, review.request)
        }
        f.api.apiIdentity = "https://another.example.test/v1/"
        failure(PurchaseIssue.Configuration) { f.useCases.refreshPurchaseContext(f.accountId) }
        assertTrue(f.api.submissions.isEmpty())
    }

    /** A late quote from a replaced credential cannot publish terms or submit as the new owner. */
    @Test
    fun replacedSessionRejectsLateResponseAndUnauthorizedCleanupIsConditional() = runTest {
        val f = PurchaseFixture()
        f.api.quoteGate = CompletableDeferred()
        val old = requireNotNull(f.sessions.value)
        val newer = old.copy(accessToken = "b".repeat(43))
        val review = async { runCatching { f.useCases.reviewPurchase(f.accountId, f.input) } }
        yield()
        f.sessions.value = newer
        f.api.quoteGate!!.complete(Unit)
        assertEquals(
            PurchaseIssue.SessionChanged,
            (review.await().exceptionOrNull() as PurchaseFailure).issue,
        )
        f.authentication.beforeIdentity = { f.sessions.value = newer }
        f.sessions.value = old
        f.authentication.failure = AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        failure(PurchaseIssue.SignInRequired) { f.useCases.refreshPurchaseContext(f.accountId) }
        assertEquals(newer, f.sessions.value)
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Uncorrelated receipts never become final UI progress or discard recovery records. */
    @Test
    fun mismatchedQuoteTermsRemainUnconfirmedAndPendingCannotBeAcknowledged() = runTest {
        val f = PurchaseFixture()
        val review = f.useCases.reviewPurchase(f.accountId, f.input)
        f.api.quoted =
            f.api.quoted.copy(
                feeMinorUnits = 999.toBigInteger(),
                totalDebitMinorUnits = 10999.toBigInteger(),
            )
        val unknown = f.useCases.submitPurchase(f.accountId, review)
        assertNull(unknown.payment)
        assertEquals(PurchaseIssue.InvalidResponse, unknown.issue)
        failure(PurchaseIssue.Unavailable) { f.useCases.payments.acknowledge(unknown) }
        assertFalse(f.store.records.single().acknowledged)
    }

    /** Cancellation after a durable commit keeps recovery but never begins a new POST. */
    @Test
    fun cancellationDuringPersistenceDoesNotSubmitAfterTheUserLeaves() = runTest {
        val f = PurchaseFixture()
        val review = f.useCases.reviewPurchase(f.accountId, f.input)
        f.store.saveGate = CompletableDeferred()
        val action =
            launch(start = CoroutineStart.UNDISPATCHED) {
                f.useCases.submitPurchase(f.accountId, review)
            }
        action.cancel()
        f.store.saveGate!!.complete(Unit)
        action.join()
        assertTrue(f.api.submissions.isEmpty())
        assertEquals(listOf(review.request), f.store.records)
    }

    /** Receipt/recovery reads remain reachable when the wallet or catalogue cannot load. */
    @Test
    fun savedRequestsRemainRecoverableDuringDisplayOutages() = runTest {
        val f = PurchaseFixture()
        val review = f.useCases.reviewPurchase(f.accountId, f.input)
        f.useCases.submitPurchase(f.accountId, review)
        f.wallets.failure =
            com.kesofty.axiapay.wallet.domain.WalletFailure(
                com.kesofty.axiapay.wallet.domain.WalletIssue.Network,
            )
        val context = f.useCases.refreshPurchaseContext(f.accountId)
        assertNull(context.wallet)
        assertEquals(listOf(review.request), context.requests)
        assertNotNull(f.useCases.payments.recover(f.accountId, review.request).payment)
        f.wallets.failure = null
        f.api.catalogueFailure = PurchaseIssue.Unavailable
        assertEquals(
            listOf(review.request),
            f.useCases.refreshPurchaseContext(f.accountId).requests,
        )
        assertEquals(1, f.api.submissions.size)
        f.wallets.failure =
            com.kesofty.axiapay.wallet.domain.WalletFailure(
                com.kesofty.axiapay.wallet.domain.WalletIssue.InvalidSession,
            )
        failure(PurchaseIssue.SignInRequired) { f.useCases.refreshPurchaseContext(f.accountId) }
    }

    /** Assertions retain classified failures rather than relying on raw upstream strings. */
    private suspend fun failure(expected: PurchaseIssue, block: suspend () -> Any?) {
        try {
            block()
            fail("Expected $expected")
        } catch (error: PurchaseFailure) {
            assertEquals(expected, error.issue)
        }
    }
}
