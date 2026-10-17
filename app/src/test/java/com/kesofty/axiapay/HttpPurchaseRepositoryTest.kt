package com.kesofty.axiapay

import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.data.purchases.HttpPurchaseRepository
import com.kesofty.axiapay.domain.purchases.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Validates the documented purchase contract over real HTTP with synthetic credentials only. */
class HttpPurchaseRepositoryTest {
    private val server = MockWebServer()
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    private lateinit var api: HttpPurchaseRepository
    private val f = PurchaseFixture()

    /** Use the same uncached bounded transport as production against a loopback test server. */
    @Before
    fun start() {
        server.start()
        api =
            HttpPurchaseRepository(
                ApiHttpClient(server.url("/v1/").toString(), true, client, Dispatchers.IO),
            )
    }

    /** Close all test-owned sockets and threads. */
    @After
    fun close() {
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** Safe JSON fixtures use exact string amounts and explicit booleans/nulls. */
    private fun response(code: Int, json: String) =
        MockResponse.Builder()
            .code(code)
            .setHeader("Content-Type", "application/json")
            .body(json)
            .build()

    /** Describe public airtime terms without internal provider SKU identifiers. */
    private fun product(id: String = f.api.product.id) =
        JSONObject()
            .put("id", id)
            .put("serviceType", "airtime")
            .put("allowanceLabel", JSONObject.NULL)
            .put("validitySeconds", JSONObject.NULL)
            .put("networkCode", "mtn")
            .put("displayName", "MTN airtime")
            .put("currency", "NGN")
            .put("fixedPriceMinorUnits", JSONObject.NULL)
            .put("minimumAmountMinorUnits", "5000")
            .put("maximumAmountMinorUnits", "1000000")
            .put("amountStepMinorUnits", "50")
            .put("feeMinorUnits", "100")
            .put("isSubmissionAvailable", true)

    /** Match quote and embedded payment-review DTOs, including the capability distinction. */
    private fun quote(capability: Boolean = true): JSONObject {
        val q = f.api.quoted
        val json =
            JSONObject()
                .put("id", q.id)
                .put("walletId", q.walletId)
                .put("productId", q.productId)
                .put("serviceType", "airtime")
                .put("allowanceLabel", JSONObject.NULL)
                .put("validitySeconds", JSONObject.NULL)
                .put("networkCode", q.providerCode)
                .put("displayName", q.displayName)
                .put("recipientPhoneNumber", q.recipientIdentifier)
                .put("currency", q.currency)
                .put("principalMinorUnits", "10000")
                .put("feeMinorUnits", "100")
                .put("totalDebitMinorUnits", "10100")
                .put("createdAt", q.createdAt.toString())
                .put("expiresAt", q.expiresAt.toString())
        if (capability) {
            json.put("isSubmissionAvailable", true)
        }
        return json
    }

    /** Correlate status responses to the original stable key. */
    private fun payment(request: PurchaseRequest, status: String = "pending") =
        JSONObject()
            .put("id", "123e4567-e89b-42d3-a456-426614174004")
            .put("requestKey", request.requestKey)
            .put("paymentStatus", status)
            .put("quote", quote(false))
            .put("createdAt", f.now.toString())
            .put("submissionStartedAt", JSONObject.NULL)

    /** Catalogue pages retain account bearer and effective filters, without demo fallback. */
    @Test
    fun cataloguePaginationRetainsFiltersAndBearer() = runTest {
        val cursor = f.api.product.id
        val second = "123e4567-e89b-42d3-a456-426614174009"
        server.enqueue(
            response(
                200,
                JSONObject()
                    .put("products", JSONArray().put(product()))
                    .put("nextCursor", cursor)
                    .toString(),
            ),
        )
        server.enqueue(
            response(
                200,
                JSONObject()
                    .put("products", JSONArray().put(product(second)))
                    .put("nextCursor", JSONObject.NULL)
                    .toString(),
            ),
        )
        assertEquals(listOf(cursor, second), api.products("synthetic-token", "NGN").map { it.id })
        for (index in 0..1) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("Bearer synthetic-token", request.headers["Authorization"])
            assertEquals("no-store", request.headers["Cache-Control"])
            assertTrue(
                request.target.startsWith(
                    "/v1/service-catalog/products?serviceType=airtime&currency=NGN&limit=25",
                ),
            )
            assertEquals(index == 1, request.target.contains("afterId=$cursor"))
        }
    }

    /**
     * Quote review uses the real wallet and exact minor units; confirmation sends only quote/key.
     */
    @Test
    fun quoteAndPurchaseBodiesMatchFirstPartyApi() = runTest {
        server.enqueue(response(201, quote().toString()))
        val quote = api.quote("synthetic-token", f.wallets.wallet.id, f.input)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/payments/quotes", request.target)
        val body = JSONObject(request.body!!.utf8())
        assertEquals(
            setOf("walletId", "productId", "recipientPhoneNumber", "amountMinorUnits"),
            body.keys().asSequence().toSet(),
        )
        assertEquals(f.wallets.wallet.id, body.getString("walletId"))
        assertEquals("10000", body.get("amountMinorUnits"))
        val saved =
            PurchaseRequest(
                PurchaseOwner(f.accountId, api.apiIdentity),
                "123e4567-e89b-42d3-a456-426614174005",
                quote.id,
                f.now,
            )
        server.enqueue(response(200, payment(saved).toString()))
        assertEquals(PurchasePaymentStatus.Pending, api.submit("synthetic-token", saved).status)
        val post = server.takeRequest()
        assertEquals("/v1/payments", post.target)
        val command = JSONObject(post.body!!.utf8())
        assertEquals(setOf("quoteId", "requestKey"), command.keys().asSequence().toSet())
        assertEquals(saved.requestKey, command.getString("requestKey"))
        assertEquals("Bearer synthetic-token", post.headers["Authorization"])
        assertEquals(2, server.requestCount)
    }

    /** Embedded progress omits capability; every documented status remains distinct. */
    @Test
    fun statusReadsAndExplicitReconciliationNeverUseANewSubmission() = runTest {
        val saved =
            PurchaseRequest(
                PurchaseOwner(f.accountId, api.apiIdentity),
                "123e4567-e89b-42d3-a456-426614174005",
                f.api.quoted.id,
                f.now,
            )
        val statuses = listOf("awaiting_funds", "reserved", "pending", "succeeded", "failed")
        for ((index, status) in statuses.withIndex()) {
            server.enqueue(response(200, payment(saved, status).toString()))
            val observed = requireNotNull(api.findByRequestKey("token", saved.requestKey))
            assertEquals(PurchasePaymentStatus.entries[index], observed.status)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(
                "/v1/payments/by-request-key?requestKey=${saved.requestKey}",
                request.target,
            )
        }
        server.enqueue(response(200, payment(saved, "succeeded").toString()))
        val final = api.reconcile("token", "123e4567-e89b-42d3-a456-426614174004")
        assertTrue(final.isFinal)
        assertEquals(
            "/v1/payments/123e4567-e89b-42d3-a456-426614174004/reconcile",
            server.takeRequest().target,
        )
    }

    /**
     * Error codes are classified, private messages discarded, and malformed success fails closed.
     */
    @Test
    fun safeErrorsAndMalformedMoneyNeverBecomeSuccess() = runTest {
        for ((status, code, issue) in
            listOf(
                Triple(409, "QUOTE_EXPIRED", PurchaseIssue.QuoteExpired),
                Triple(400, "INVALID_PAYMENT", PurchaseIssue.InvalidPayment),
                Triple(409, "INSUFFICIENT_FUNDS", PurchaseIssue.InsufficientFunds),
                Triple(401, "UNAUTHORIZED", PurchaseIssue.SignInRequired),
                Triple(503, "PAYMENT_UNAVAILABLE", PurchaseIssue.Unavailable),
            )) {
            server.enqueue(
                response(
                    status,
                    JSONObject()
                        .put("code", code)
                        .put("message", "private provider diagnostics")
                        .toString(),
                ),
            )
            try {
                api.quote("secret", f.wallets.wallet.id, f.input)
                fail()
            } catch (failure: PurchaseFailure) {
                assertEquals(issue, failure.issue)
                assertFalse(failure.toString().contains("private"))
            }
        }
        for (invalid in
            listOf(
                quote().put("principalMinorUnits", 10000),
                quote().put("totalDebitMinorUnits", "1"),
                quote().put("isSubmissionAvailable", "true"),
            )) {
            server.enqueue(response(201, invalid.toString()))
            try {
                api.quote("token", f.wallets.wallet.id, f.input)
                fail()
            } catch (failure: PurchaseFailure) {
                assertEquals(PurchaseIssue.InvalidResponse, failure.issue)
            }
        }
        server.enqueue(response(404, "{}"))
        assertNull(api.findByRequestKey("token", "saved-key"))
        assertEquals(9, server.requestCount)
    }

    /** Data products require a fixed price matching both bounds and complete public plan terms. */
    private fun dataProduct() =
        product()
            .put("serviceType", "data")
            .put("fixedPriceMinorUnits", "125050")
            .put("minimumAmountMinorUnits", "125050")
            .put("maximumAmountMinorUnits", "125050")
            .put("allowanceLabel", "1 GB")
            .put("validitySeconds", 2592000)

    /** Data quote snapshots retain the complete required plan description and exact total. */
    private fun dataQuote() =
        quote()
            .put("serviceType", "data")
            .put("principalMinorUnits", "125050")
            .put("totalDebitMinorUnits", "125150")
            .put("allowanceLabel", "1 GB")
            .put("validitySeconds", 2592000)

    /** Data filters persist across pages and fixed-plan quote requests never override a price. */
    @Test
    fun dataPlanCatalogueAndQuoteUseExactApiContracts() = runTest {
        val plan = dataProduct()
        server.enqueue(
            response(
                200,
                JSONObject()
                    .put("products", JSONArray().put(plan))
                    .put("nextCursor", JSONObject.NULL)
                    .toString(),
            ),
        )
        val selected = api.products("synthetic-token", "NGN", PurchaseService.Data).single()
        assertEquals("125050", selected.fixedMinorUnits.toString())
        assertEquals("1 GB", selected.allowanceLabel)
        assertEquals(2592000L, selected.validitySeconds)
        assertEquals(
            "/v1/service-catalog/products?serviceType=data&currency=NGN&limit=25",
            server.takeRequest().target,
        )
        val review = dataQuote()
        server.enqueue(response(201, review.toString()))
        val prepared =
            requireNotNull(
                com.kesofty.axiapay.application.purchases.PreparePurchase()(
                        selected,
                        "08012345678",
                        "ignored",
                    )
                    .input,
            )
        val result = api.quote("synthetic-token", f.wallets.wallet.id, prepared)
        assertEquals(PurchaseService.Data, result.service)
        assertEquals(2592000L, result.validitySeconds)
        val post = server.takeRequest()
        val body = JSONObject(post.body!!.utf8())
        assertFalse(body.has("amountMinorUnits"))
        assertFalse(body.has("serviceType"))
        assertEquals(3, body.length())
        assertEquals(f.wallets.wallet.id, body.getString("walletId"))
        assertEquals("Bearer synthetic-token", post.headers["Authorization"])
    }

    /**
     * Wrong service pages, variable data prices and incomplete terms cannot become purchasable
     * plans.
     */
    @Test
    fun dataCatalogueRejectsOtherServicesAndMalformedTerms() = runTest {
        for (row in
            listOf(
                product(),
                dataProduct().put("validitySeconds", 1.5),
                dataProduct().put("validitySeconds", "2592000"),
                dataProduct().put("allowanceLabel", ""),
                dataProduct().put("fixedPriceMinorUnits", JSONObject.NULL),
                dataProduct().put("minimumAmountMinorUnits", "5000"),
                dataProduct().put("maximumAmountMinorUnits", "1000000"),
                dataProduct().put("allowanceLabel", JSONObject.NULL),
                dataProduct().put("validitySeconds", JSONObject.NULL),
            )) {
            server.enqueue(
                response(
                    200,
                    JSONObject()
                        .put("products", JSONArray().put(row))
                        .put("nextCursor", JSONObject.NULL)
                        .toString(),
                ),
            )
            try {
                api.products("token", "NGN", PurchaseService.Data)
                fail()
            } catch (failure: PurchaseFailure) {
                assertEquals(PurchaseIssue.InvalidResponse, failure.issue)
            }
        }
        assertEquals(9, server.requestCount)
    }

    /** Missing required data terms cannot become a review or a successful payment receipt. */
    @Test
    fun dataQuotesAndReceiptsRejectMissingPlanTerms() = runTest {
        val saved = f.workflow.review(f.accountId, f.input).request
        for (invalid in
            listOf(
                dataQuote().put("allowanceLabel", JSONObject.NULL),
                dataQuote().put("validitySeconds", JSONObject.NULL),
            )) {
            server.enqueue(response(201, invalid.toString()))
            try {
                api.quote(
                    "token",
                    f.wallets.wallet.id,
                    f.input.copy(service = PurchaseService.Data),
                )
                fail()
            } catch (failure: PurchaseFailure) {
                assertEquals(PurchaseIssue.InvalidResponse, failure.issue)
            }
            invalid.remove("isSubmissionAvailable")
            server.enqueue(
                response(200, payment(saved, "succeeded").put("quote", invalid).toString()),
            )
            try {
                api.findByRequestKey("token", saved.requestKey)
                fail()
            } catch (failure: PurchaseFailure) {
                assertEquals(PurchaseIssue.InvalidResponse, failure.issue)
            }
        }
        assertEquals(4, server.requestCount)
    }

    /** Electricity products have explicit server biller/mode and variable exact amount bounds. */
    private fun electricityProduct() =
        product()
            .put("serviceType", "electricity")
            .put("networkCode", JSONObject.NULL)
            .put("billerCode", "ikeja-electric")
            .put("electricityMeterType", "prepaid")

    /** Verification fixtures preserve exact meter strings and server customer evidence. */
    private fun meter() =
        JSONObject()
            .put("id", "123e4567-e89b-42d3-a456-426614174030")
            .put("productId", f.api.product.id)
            .put("serviceType", "electricity")
            .put("billerCode", "ikeja-electric")
            .put("electricityMeterType", "prepaid")
            .put("accountNumber", "00123456789")
            .put("customerName", "Test customer")
            .put("serviceAddress", "Test address")
            .put("verifiedAt", f.now.toString())
            .put("expiresAt", f.now.plusSeconds(120).toString())

    /** Quotes embed the verified account and keep delivery contact distinct from a meter. */
    private fun electricityQuote() =
        quote()
            .put("serviceType", "electricity")
            .put("networkCode", JSONObject.NULL)
            .put("billerCode", "ikeja-electric")
            .put("electricityMeterType", "prepaid")
            .put("recipientPhoneNumber", JSONObject.NULL)
            .put("contactPhoneNumber", "+2348012345678")
            .put("accountValidation", meter())

    /** Complete prepaid receipts retain ordered KCT/recharge tokens and exact decimal units. */
    private fun prepaidReceipt() =
        JSONObject()
            .put("fulfilmentType", "electricity_prepaid")
            .put("receiptReference", "receipt-123")
            .put("unitsKilowattHours", "12.375")
            .put(
                "tokens",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("label", "Key change")
                            .put("value", "00000000000000000001"),
                    )
                    .put(
                        JSONObject().put("label", "Recharge").put("value", "00000000000000000002"),
                    ),
            )

    /** Match financial and fulfilment states independently under one original identity. */
    private fun electricityPayment(receipt: JSONObject? = prepaidReceipt()) =
        payment(
                com.kesofty.axiapay.domain.purchases.PurchaseRequest(
                    PurchaseOwner(f.accountId, api.apiIdentity),
                    "123e4567-e89b-42d3-a456-426614174005",
                    f.api.quoted.id,
                    f.now,
                    service = PurchaseService.Electricity,
                ),
                "succeeded",
            )
            .put("quote", electricityQuote().also { it.remove("isSubmissionAvailable") })
            .put("fulfilmentStatus", if (receipt == null) "pending" else "available")
            .put("fulfilment", receipt ?: JSONObject.NULL)

    /**
     * Meter verification and quotes use documented endpoints and never substitute phone recipients.
     */
    @Test
    fun electricityVerificationCatalogueAndQuoteMatchApi() = runTest {
        server.enqueue(
            response(
                200,
                JSONObject()
                    .put("products", JSONArray().put(electricityProduct()))
                    .put("nextCursor", JSONObject.NULL)
                    .toString(),
            ),
        )
        val product = api.products("token", "NGN", PurchaseService.Electricity).single()
        assertEquals("ikeja-electric", product.providerCode)
        assertEquals(com.kesofty.axiapay.domain.ElectricityMeterType.Prepaid, product.meterType)
        assertEquals(
            "/v1/service-catalog/products?serviceType=electricity&currency=NGN&limit=25",
            server.takeRequest().target,
        )
        server.enqueue(response(201, meter().toString()))
        val validation = api.verifyMeter("token", product.id, "00123456789")
        val verify = server.takeRequest()
        assertEquals("/v1/payments/account-validations", verify.target)
        assertEquals("Bearer token", verify.headers["Authorization"])
        assertEquals("00123456789", JSONObject(verify.body!!.utf8()).get("accountNumber"))
        server.enqueue(response(201, electricityQuote().toString()))
        val prepared =
            requireNotNull(
                com.kesofty.axiapay.application.purchases.PreparePurchase()(
                        product,
                        validation.accountNumber,
                        "100",
                        VerifiedMeter(PurchaseOwner(f.accountId, api.apiIdentity), validation),
                        "08012345678",
                    )
                    .input,
            )
        val result = api.quote("token", f.wallets.wallet.id, prepared)
        assertEquals(validation, result.meterValidation)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals(
            setOf(
                "walletId",
                "productId",
                "accountValidationId",
                "contactPhoneNumber",
                "amountMinorUnits",
            ),
            body.keys().asSequence().toSet(),
        )
        assertEquals("10000", body.get("amountMinorUnits"))
        assertEquals(validation.id, body.get("accountValidationId"))
    }

    /** Financial success can lack a token; explicit lookup recovers exact ordered fulfilment. */
    @Test
    fun electricityPendingPrepaidAndPostpaidReceiptsAreDistinct() = runTest {
        server.enqueue(response(200, electricityPayment(null).toString()))
        val pending = api.findByRequestKey("token", "key")!!
        assertTrue(pending.needsReceipt)
        assertTrue(pending.canReconcile)
        assertFalse(pending.canAcknowledge)
        server.enqueue(response(200, electricityPayment().toString()))
        val complete = api.reconcile("token", pending.id)
        assertEquals("12.375", complete.electricityReceipt!!.unitsKilowattHours)
        assertEquals(
            listOf("00000000000000000001", "00000000000000000002"),
            complete.electricityReceipt.tokens.map { it.value },
        )
        assertTrue(complete.canAcknowledge)
        val postpaid =
            electricityPayment(
                JSONObject()
                    .put("fulfilmentType", "electricity_postpaid")
                    .put("receiptReference", "postpaid-receipt"),
            )
        postpaid
            .getJSONObject("quote")
            .put("electricityMeterType", "postpaid")
            .getJSONObject("accountValidation")
            .put("electricityMeterType", "postpaid")
        server.enqueue(response(200, postpaid.toString()))
        val paid = api.findByRequestKey("token", "key")!!
        assertTrue(paid.electricityReceipt!!.tokens.isEmpty())
        assertEquals(
            com.kesofty.axiapay.domain.ElectricityMeterType.Postpaid,
            paid.electricityReceipt.meterType,
        )
    }

    /** Reject malformed/mismatched private evidence instead of fabricating successful receipts. */
    @Test
    fun electricityMalformedMetersAndReceiptsFailClosed() = runTest {
        for (invalid in
            listOf(
                meter().put("accountNumber", 123456789),
                meter().put("electricityMeterType", "unknown"),
                meter().put("expiresAt", f.now.toString()),
                meter().put("customerName", ""),
            )) {
            server.enqueue(response(201, invalid.toString()))
            try {
                api.verifyMeter("token", f.api.product.id, "00123456789")
                fail()
            } catch (failure: PurchaseFailure) {
                assertEquals(PurchaseIssue.InvalidResponse, failure.issue)
            }
        }
        for (invalid in
            listOf(
                electricityPayment(prepaidReceipt().put("tokens", JSONArray())),
                electricityPayment(prepaidReceipt().put("unitsKilowattHours", 12.375)),
                electricityPayment(prepaidReceipt().put("fulfilmentType", "electricity_postpaid")),
                electricityPayment(
                    prepaidReceipt()
                        .put(
                            "tokens",
                            JSONArray()
                                .put(JSONObject().put("label", "a").put("value", "same"))
                                .put(JSONObject().put("label", "b").put("value", "same")),
                        ),
                ),
                electricityPayment().put("fulfilmentStatus", "pending"),
                electricityPayment(null).put("fulfilmentStatus", "not_available"),
            )) {
            server.enqueue(response(200, invalid.toString()))
            try {
                api.findByRequestKey("token", "key")
                fail()
            } catch (failure: PurchaseFailure) {
                assertEquals(PurchaseIssue.InvalidResponse, failure.issue)
            }
        }
    }

    /** Provider verification failures remain safe actionable classifications before purchase. */
    @Test
    fun electricityVerificationErrorsRemainDistinct() = runTest {
        for ((code, issue) in
            listOf(
                "ACCOUNT_INVALID" to PurchaseIssue.InvalidMeter,
                "ACCOUNT_UNCONFIRMED" to PurchaseIssue.MeterUnconfirmed,
                "ACCOUNT_VALIDATION_EXPIRED" to PurchaseIssue.MeterVerificationExpired,
            )) {
            server.enqueue(
                response(
                    503,
                    JSONObject().put("code", code).put("message", "secret meter detail").toString(),
                ),
            )
            try {
                api.verifyMeter("token", f.api.product.id, "00123456789")
                fail()
            } catch (failure: PurchaseFailure) {
                assertEquals(issue, failure.issue)
                assertFalse(failure.toString().contains("secret"))
            }
        }
    }
}
