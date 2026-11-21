package com.kesofty.axiapay

import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.wallet.data.FundingRepositoryImpl
import com.kesofty.axiapay.wallet.data.remote.FundingRemoteDataSource
import com.kesofty.axiapay.wallet.domain.*
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Verify the real HTTP contract without tokens, bank credentials or live financial effects. */
class FundingRepositoryImplTest {
    private val server = MockWebServer()
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    private lateinit var funding: FundingRepositoryImpl
    private val id = "123e4567-e89b-42d3-a456-426614174004"
    private val wallet = "123e4567-e89b-42d3-a456-426614174001"

    /** Bind a disposable loopback API to the production transport. */
    @Before
    fun start() {
        server.start()
        funding =
            FundingRepositoryImpl(
                FundingRemoteDataSource(
                    ApiHttpClient(server.url("/api/v1/").toString(), true, client, Dispatchers.IO),
                ),
            )
    }

    /** Release calls and sockets. */
    @After
    fun close() {
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** Supply an exact response, including a null journal for all uncredited states. */
    private fun response(
        status: String = "awaiting_confirmation",
        operation: Any = JSONObject.NULL,
    ): String =
        JSONObject()
            .put("fundingId", id)
            .put("walletId", wallet)
            .put("fundingOptionId", "vtpass-bank-transfer")
            .put("currency", "NGN")
            .put("amountMinorUnits", "10000")
            .put("fundingStatus", status)
            .put("checkoutUrl", JSONObject.NULL)
            .put("operationId", operation)
            .toString()

    /** Enqueue safe JSON with explicit status. */
    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    /** Serialize prepared minor units once and submit only reference evidence for human review. */
    @Test
    fun manualRequestAndReferenceNeverSendCustomerSuccessFlags() = runTest {
        enqueue(response("awaiting_transfer"), 202)
        funding.create(
            "fixture-token",
            wallet,
            FundingInput("vtpass-bank-transfer", BigInteger("10000"), null, "stable-key"),
        )
        val create = server.takeRequest()
        val body = JSONObject(create.body!!.utf8())
        assertEquals("10000", body.get("amountMinorUnits"))
        assertEquals("stable-key", body.getString("requestKey"))
        assertFalse(body.has("payerEmail"))
        assertFalse(body.has("ownerId"))
        enqueue(response())
        val pending = funding.submitReference("fixture-token", id, "transfer-123")
        val submit = server.takeRequest()
        assertEquals("/api/v1/wallet-funding/$id/reference", submit.target)
        assertEquals(
            setOf("paymentReference"),
            JSONObject(submit.body!!.utf8()).keys().asSequence().toSet(),
        )
        assertEquals(FundingStatus.AwaitingConfirmation, pending.status)
    }

    /** Only a documented pre-submission validation rejection permits corrected terms. */
    @Test
    fun distinguishesRejectedInputFromUncertainInitialization() = runTest {
        val input = FundingInput("hosted-checkout", BigInteger("10000"), "bad-email", "original")
        for ((code, issue) in
            listOf(
                "INVALID_INPUT" to FundingIssue.InvalidInput,
                "INVALID_FUNDING" to FundingIssue.Unavailable,
            )) {
            enqueue(JSONObject().put("code", code).toString(), 400)
            try {
                funding.create("fixture-token", wallet, input)
                fail("Expected the server rejection")
            } catch (failure: FundingFailure) {
                assertEquals(issue, failure.issue)
            }
        }
    }

    /**
     * Unknown/forged success and insecure checkout URLs never enter presentation as trusted
     * results.
     */
    @Test
    fun malformedSuccessAndCheckoutAreRejected() = runTest {
        val invalid =
            listOf(
                response("credited"),
                response("success"),
                JSONObject(response()).put("checkoutUrl", "http://attacker.test").toString(),
                JSONObject(response()).put("amountMinorUnits", 10000).toString(),
            )
        for (body in invalid) {
            enqueue(body)
            try {
                funding.get("fixture-token", id)
                fail("Malformed funding accepted")
            } catch (failure: FundingFailure) {
                assertEquals(FundingIssue.InvalidResponse, failure.issue)
            }
        }
    }
}
