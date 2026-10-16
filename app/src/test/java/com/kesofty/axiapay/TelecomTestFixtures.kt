package com.kesofty.axiapay

import com.kesofty.axiapay.application.telecom.TelecomPurchases
import com.kesofty.axiapay.domain.auth.StoredAuthenticationSession
import com.kesofty.axiapay.domain.telecom.*
import java.math.BigInteger
import java.time.*
import kotlinx.coroutines.*

/** Isolated ports and exact server-shaped values shared by purchase and reducer tests. */
internal class TelecomFixture {
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
    val store = MemoryTelecomRequests()
    val api = RecordingTelecomRepository(now, wallets.wallet.id)
    val workflow =
        TelecomPurchases(authentication, sessions, wallets, api, store, clock) {
            "123e4567-e89b-42d3-a456-426614174005"
        }
    val input = TelecomQuoteInput(api.product.id, "+2348012345678", BigInteger.valueOf(10000))
}

/** Retains original references without exposing a mutable backing list to callers. */
internal class MemoryTelecomRequests : TelecomRequestStore {
    var records = emptyList<TelecomRequest>()
    var failure = false
    var saveGate: CompletableDeferred<Unit>? = null

    override suspend fun read(owner: TelecomOwner) = records.filter { it.owner == owner }

    override suspend fun save(request: TelecomRequest) {
        if (failure) {
            throw TelecomFailure(TelecomIssue.Storage)
        }
        records =
            records.filterNot { it.owner == request.owner && it.requestKey == request.requestKey } +
                request
        saveGate?.let { withContext(NonCancellable) { it.await() } }
    }

    override suspend fun acknowledge(request: TelecomRequest) {
        records = records.map { if (it == request) it.copy(acknowledged = true) else it }
    }
}

/** Observable API port supports uncertainty and cancellation-resistant responses. */
internal class RecordingTelecomRepository(now: Instant, walletId: String) : TelecomRepository {
    override var apiIdentity = "https://api.example.test/v1/"
    val product =
        TelecomProduct(
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
    var catalogue = listOf(product)
    var requestedService: TelecomService? = null
    var quoted =
        TelecomQuote(
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
    var quotedInput: TelecomQuoteInput? = null
    var productsCalls = 0
    var catalogueFailure: TelecomIssue? = null
    var quoteCalls = 0
    var gets = 0
    var reconciles = 0
    val submissions = mutableListOf<TelecomRequest>()
    var status = TelecomPaymentStatus.Pending
    var observed: TelecomPayment? = null
    var submitFailure: TelecomIssue? = null
    var beforeSubmit: (() -> Unit)? = null
    var quoteGate: CompletableDeferred<Unit>? = null
    var submitGate: CompletableDeferred<Unit>? = null

    override suspend fun products(
        accessToken: String,
        currency: String,
        service: TelecomService,
    ): List<TelecomProduct> {
        productsCalls++
        requestedService = service
        catalogueFailure?.let { throw TelecomFailure(it) }
        return catalogue.filter { it.service == service }
    }

    override suspend fun quote(
        accessToken: String,
        walletId: String,
        input: TelecomQuoteInput,
    ): TelecomQuote {
        quoteCalls++
        quoteWallet = walletId
        quotedInput = input
        val snapshot = quoted
        quoteGate?.let { withContext(NonCancellable) { it.await() } }
        return snapshot
    }

    override suspend fun getQuote(accessToken: String, quoteId: String) = quoted

    override suspend fun submit(accessToken: String, request: TelecomRequest): TelecomPayment {
        submissions += request
        beforeSubmit?.invoke()
        submitGate?.let { withContext(NonCancellable) { it.await() } }
        submitFailure?.let { throw TelecomFailure(it) }
        return payment(request).also { observed = it }
    }

    override suspend fun findByRequestKey(
        accessToken: String,
        requestKey: String,
    ): TelecomPayment? {
        gets++
        return observed
    }

    override suspend fun reconcile(accessToken: String, paymentId: String): TelecomPayment {
        reconciles++
        return requireNotNull(observed).copy(status = status).also { observed = it }
    }

    /** Produces only documented statuses with the original request and quote correlation. */
    fun payment(request: TelecomRequest) =
        TelecomPayment(
            "123e4567-e89b-42d3-a456-426614174004",
            request.requestKey,
            status,
            quoted.copy(isSubmissionAvailable = false),
            quoted.createdAt,
            quoted.createdAt,
        )
}
