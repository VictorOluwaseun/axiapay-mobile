package com.kesofty.axiapay

import com.kesofty.axiapay.application.airtime.AirtimePurchases
import com.kesofty.axiapay.domain.airtime.*
import com.kesofty.axiapay.domain.auth.StoredAuthenticationSession
import java.math.BigInteger
import java.time.*
import kotlinx.coroutines.*

/** Isolated ports and exact server-shaped values shared by purchase and reducer tests. */
internal class AirtimeFixture {
    val now = Instant.parse("2026-10-16T12:00:00Z")
    val clock = MutableAuthenticationClock(now)
    val authentication = RecordingAuthenticationRepository(now)
    val accountId = authentication.session.account.id
    val sessions =
        MemoryAuthenticationSessionStore(
            StoredAuthenticationSession(
                authentication.session.accessToken,
                now.plusSeconds(3600),
                authentication.apiIdentity,
            ),
        )
    val wallets = RecordingWalletRepository()
    val store = MemoryAirtimeRequests()
    val api = RecordingAirtimeRepository(now, wallets.wallet.id)
    val workflow =
        AirtimePurchases(authentication, sessions, wallets, api, store, clock) {
            "123e4567-e89b-42d3-a456-426614174005"
        }
    val input = AirtimeQuoteInput(api.product.id, "+2348012345678", BigInteger.valueOf(10000))
}

/** Retains original references without exposing a mutable backing list to callers. */
internal class MemoryAirtimeRequests : AirtimeRequestStore {
    var records = emptyList<AirtimeRequest>()
    var failure = false
    var saveGate: CompletableDeferred<Unit>? = null

    override suspend fun read(owner: AirtimeOwner) = records.filter { it.owner == owner }

    override suspend fun save(request: AirtimeRequest) {
        if (failure) {
            throw AirtimeFailure(AirtimeIssue.Storage)
        }
        records =
            records.filterNot { it.owner == request.owner && it.requestKey == request.requestKey } +
                request
        saveGate?.let { withContext(NonCancellable) { it.await() } }
    }

    override suspend fun acknowledge(request: AirtimeRequest) {
        records = records.map { if (it == request) it.copy(acknowledged = true) else it }
    }
}

/** Observable API port supports uncertainty and cancellation-resistant responses. */
internal class RecordingAirtimeRepository(now: Instant, walletId: String) : AirtimeRepository {
    override var apiIdentity = "https://api.example.test/v1/"
    val product =
        AirtimeProduct(
            "123e4567-e89b-42d3-a456-426614174002",
            "MTN airtime",
            "mtn",
            "NGN",
            null,
            BigInteger.valueOf(5000),
            BigInteger.valueOf(1000000),
            BigInteger.valueOf(50),
            BigInteger.valueOf(100),
            true,
        )
    var quoted =
        AirtimeQuote(
            "123e4567-e89b-42d3-a456-426614174003",
            walletId,
            product.id,
            product.displayName,
            product.networkCode,
            "+2348012345678",
            "NGN",
            BigInteger.valueOf(10000),
            BigInteger.valueOf(100),
            BigInteger.valueOf(10100),
            now,
            now.plusSeconds(120),
            true,
        )
    var quoteWallet: String? = null
    var quotedInput: AirtimeQuoteInput? = null
    var productsCalls = 0
    var catalogueFailure: AirtimeIssue? = null
    var quoteCalls = 0
    var gets = 0
    var reconciles = 0
    val submissions = mutableListOf<AirtimeRequest>()
    var status = AirtimePaymentStatus.Pending
    var observed: AirtimePayment? = null
    var submitFailure: AirtimeIssue? = null
    var beforeSubmit: (() -> Unit)? = null
    var quoteGate: CompletableDeferred<Unit>? = null
    var submitGate: CompletableDeferred<Unit>? = null

    override suspend fun products(accessToken: String, currency: String): List<AirtimeProduct> {
        productsCalls++
        catalogueFailure?.let { throw AirtimeFailure(it) }
        return listOf(product)
    }

    override suspend fun quote(
        accessToken: String,
        walletId: String,
        input: AirtimeQuoteInput,
    ): AirtimeQuote {
        quoteCalls++
        quoteWallet = walletId
        quotedInput = input
        val snapshot = quoted
        quoteGate?.let { withContext(NonCancellable) { it.await() } }
        return snapshot
    }

    override suspend fun getQuote(accessToken: String, quoteId: String) = quoted

    override suspend fun submit(accessToken: String, request: AirtimeRequest): AirtimePayment {
        submissions += request
        beforeSubmit?.invoke()
        submitGate?.let { withContext(NonCancellable) { it.await() } }
        submitFailure?.let { throw AirtimeFailure(it) }
        return payment(request).also { observed = it }
    }

    override suspend fun findByRequestKey(
        accessToken: String,
        requestKey: String,
    ): AirtimePayment? {
        gets++
        return observed
    }

    override suspend fun reconcile(accessToken: String, paymentId: String): AirtimePayment {
        reconciles++
        return requireNotNull(observed).copy(status = status).also { observed = it }
    }

    /** Produces only documented statuses with the original request and quote correlation. */
    fun payment(request: AirtimeRequest) =
        AirtimePayment(
            "123e4567-e89b-42d3-a456-426614174004",
            request.requestKey,
            status,
            quoted.copy(isSubmissionAvailable = false),
            quoted.createdAt,
            quoted.createdAt,
        )
}
