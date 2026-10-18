package com.kesofty.axiapay

import com.kesofty.axiapay.data.http.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Exercise headers through the real shared transport, including repeated reads and payment request
 * identities.
 */
class ApiHttpClientRequestIdTest {
    private val server = MockWebServer()
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    private lateinit var http: ApiHttpClient

    /** Match production composition with explicit device labels and a fixed millisecond. */
    @Before
    fun start() {
        server.start()
        http =
            ApiHttpClient(
                server.url("/api/v1/").toString(),
                true,
                client,
                Dispatchers.IO,
                ApiRequestIdGenerator(
                    "samsung",
                    "SM-A556E",
                    Clock.fixed(Instant.ofEpochMilli(1700000000000), ZoneOffset.UTC),
                ),
            )
    }

    /** Release only this test's network resources. */
    @After
    fun close() {
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** Preserve JSON/error classification while choosing a synthetic status. */
    private fun response(status: Int = 200, id: String? = null): MockResponse {
        val builder =
            MockResponse.Builder()
                .code(status)
                .setHeader("Content-Type", "application/json")
                .body("{\"code\":\"INVALID_INPUT\"}")
        if (id != null) {
            builder.setHeader("X-Request-Id", id)
        }
        return builder.build()
    }

    /** Normal authenticated reads send exactly one fresh ID for every exchange. */
    @Test
    fun repeatedReadsCarryFreshStructuredHeaders() = runBlocking {
        repeat(3) {
            server.enqueue(response())
            http.request("wallets/me", 200, bearer = "synthetic-token")
        }
        val requests = (1..3).map { server.takeRequest() }
        val ids = requests.map { requireNotNull(it.headers["X-Request-Id"]) }
        assertEquals(3, ids.toSet().size)
        assertTrue(
            ids.all { it.startsWith("samsung-SM-A556E-1700000000000-") && isValidApiRequestId(it) },
        )
        assertTrue(requests.all { it.headers.values("X-Request-Id").size == 1 })
        assertTrue(requests.all { it.headers["Authorization"] == "Bearer synthetic-token" })
    }

    /**
     * Reconfirming an original payment changes correlation only and never changes or replays its
     * request key.
     */
    @Test
    fun explicitPaymentCallsPreserveTheSameIdempotencyKeyWithDifferentRequestIds() = runBlocking {
        val payload =
            JSONObject().put("quoteId", "original-quote").put("requestKey", "original-logical-key")
        repeat(2) {
            server.enqueue(response())
            http.request("payments", 200, payload, "synthetic-token")
        }
        val first = server.takeRequest()
        val repeated = server.takeRequest()
        assertEquals(first.body!!.utf8(), repeated.body!!.utf8())
        assertNotEquals(first.headers["X-Request-Id"], repeated.headers["X-Request-Id"])
        assertEquals(2, server.requestCount)
    }

    /** HTTP errors retain the entire bounded response reference; unsafe echoes are omitted. */
    @Test
    fun retainsBoundedServerReferencesWithoutAllowingUnsafeHeaderValues() = runBlocking {
        for (id in listOf("a".repeat(128), "unsafe/reference", "a".repeat(129))) {
            server.enqueue(response(400, id))
            try {
                http.request("auth/me", 200)
                fail("Expected HTTP rejection")
            } catch (failure: ApiRequestFailure) {
                assertEquals(ApiFailureKind.Http, failure.kind)
                assertEquals(id.takeIf(::isValidApiRequestId), failure.requestReference)
            }
        }
    }
}
