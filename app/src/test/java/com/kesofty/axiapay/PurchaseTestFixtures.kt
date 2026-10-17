package com.kesofty.axiapay

import com.kesofty.axiapay.application.purchases.WalletPurchases
import com.kesofty.axiapay.domain.auth.StoredAuthenticationSession
import com.kesofty.axiapay.domain.purchases.*
import java.math.BigInteger
import java.time.*
import kotlinx.coroutines.*

/** Isolated ports and exact server-shaped values shared by purchase and reducer tests. */
internal class PurchaseFixture {
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
    val store = MemoryPurchaseRequests()
    val api = RecordingPurchaseRepository(now, wallets.wallet.id)
    val workflow =
        WalletPurchases(authentication, sessions, wallets, api, store, clock) {
            "123e4567-e89b-42d3-a456-426614174005"
        }
    val input = PurchaseQuoteInput(api.product.id, "+2348012345678", BigInteger.valueOf(10000))
}

/** Retains original references without exposing a mutable backing list to callers. */
internal class MemoryPurchaseRequests : PurchaseRequestStore {
    var records = emptyList<PurchaseRequest>()
    var failure = false
    var saveGate: CompletableDeferred<Unit>? = null

    override suspend fun read(owner: PurchaseOwner) = records.filter { it.owner == owner }

    override suspend fun save(request: PurchaseRequest) {
        if (failure) {
            throw PurchaseFailure(PurchaseIssue.Storage)
        }
        records =
            records.filterNot { it.owner == request.owner && it.requestKey == request.requestKey } +
                request
        saveGate?.let { withContext(NonCancellable) { it.await() } }
    }

    override suspend fun acknowledge(request: PurchaseRequest) {
        records = records.map { if (it == request) it.copy(acknowledged = true) else it }
    }
}

/** Observable API port supports uncertainty and cancellation-resistant responses. */
internal class RecordingPurchaseRepository(now: Instant, walletId: String) : PurchaseRepository {
    override var apiIdentity = "https://api.example.test/v1/"
    val product =
        PurchaseProduct(
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
    var requestedService: PurchaseService? = null
    var quoted =
        PurchaseQuote(
            "123e4567-e89b-42d3-a456-426614174003",
            walletId,
            product.id,
            product.displayName,
            product.providerCode,
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
    var quotedInput: PurchaseQuoteInput? = null
    var productsCalls = 0
    var catalogueFailure: PurchaseIssue? = null
    var quoteCalls = 0
    var gets = 0
    var reconciles = 0
    val submissions = mutableListOf<PurchaseRequest>()
    var meter: MeterValidation? = null
    var verifyCalls = 0
    var verifyFailure: PurchaseIssue? = null
    var verifyGate: CompletableDeferred<Unit>? = null
    var receipt: ElectricityReceipt? = null
    var status = PurchasePaymentStatus.Pending
    var observed: PurchasePayment? = null
    var submitFailure: PurchaseIssue? = null
    var beforeSubmit: (() -> Unit)? = null
    var quoteGate: CompletableDeferred<Unit>? = null
    var submitGate: CompletableDeferred<Unit>? = null

    override suspend fun products(
        accessToken: String,
        currency: String,
        service: PurchaseService,
    ): List<PurchaseProduct> {
        productsCalls++
        requestedService = service
        catalogueFailure?.let { throw PurchaseFailure(it) }
        return catalogue.filter { it.service == service }
    }

    /** Injectable non-spending verification for correlation, expiry and late-result checks. */
    override suspend fun verifyMeter(
        accessToken: String,
        productId: String,
        accountNumber: String,
    ): MeterValidation {
        verifyCalls++
        val snapshot = requireNotNull(meter)
        verifyGate?.let { withContext(NonCancellable) { it.await() } }
        verifyFailure?.let { throw PurchaseFailure(it) }
        return snapshot
    }

    override suspend fun quote(
        accessToken: String,
        walletId: String,
        input: PurchaseQuoteInput,
    ): PurchaseQuote {
        quoteCalls++
        quoteWallet = walletId
        quotedInput = input
        val snapshot = quoted
        quoteGate?.let { withContext(NonCancellable) { it.await() } }
        return snapshot
    }

    override suspend fun getQuote(accessToken: String, quoteId: String) = quoted

    override suspend fun submit(accessToken: String, request: PurchaseRequest): PurchasePayment {
        submissions += request
        beforeSubmit?.invoke()
        submitGate?.let { withContext(NonCancellable) { it.await() } }
        submitFailure?.let { throw PurchaseFailure(it) }
        return payment(request).also { observed = it }
    }

    override suspend fun findByRequestKey(
        accessToken: String,
        requestKey: String,
    ): PurchasePayment? {
        gets++
        return observed
    }

    override suspend fun reconcile(accessToken: String, paymentId: String): PurchasePayment {
        reconciles++
        return requireNotNull(observed).copy(status = status, electricityReceipt = receipt).also {
            observed = it
        }
    }

    /** Produces only documented statuses with the original request and quote correlation. */
    fun payment(request: PurchaseRequest) =
        PurchasePayment(
            "123e4567-e89b-42d3-a456-426614174004",
            request.requestKey,
            status,
            quoted.copy(isSubmissionAvailable = false),
            quoted.createdAt,
            quoted.createdAt,
            receipt,
        )
}
