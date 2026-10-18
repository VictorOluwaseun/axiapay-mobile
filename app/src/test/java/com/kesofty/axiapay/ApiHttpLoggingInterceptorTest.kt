package com.kesofty.axiapay

import com.kesofty.axiapay.data.http.ApiHttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercise real HTTP logging without disclosing synthetic credentials or altering wire bodies. */
class ApiHttpLoggingInterceptorTest {
    private val server = MockWebServer()
    private val lines = mutableListOf<String>()
    private lateinit var client: OkHttpClient
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Reuse the production retry/redirect policy against an isolated local HTTP fixture. */
    @Before
    fun start() {
        server.start()
        client =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .callTimeout(3, TimeUnit.SECONDS)
                .addInterceptor(ApiHttpLoggingInterceptor { lines += it })
                .build()
    }

    /** Release test-owned network resources. */
    @After
    fun close() {
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** Provide bounded JSON replies through the actual OkHttp response reader. */
    private fun response(status: Int, body: String) =
        MockResponse.Builder()
            .code(status)
            .setHeader("Content-Type", "application/json")
            .setHeader("Set-Cookie", "session=private-response-cookie")
            .body(body)
            .build()

    /**
     * Public query/status fields remain useful while credentials, identity and money are masked.
     */
    @Test
    fun logsSafeStructureWithoutChangingRequestOrResponse() {
        val body =
            """{"password":"private-password","code":"001234","contact":{"kind":"email","value":"private@example.test"},"walletId":"private-wallet","amountMinorUnits":"123456789","futureSecret":"private-future-field","currency":"NGN","serviceType":"data"}"""
        val reply =
            """{"accessToken":"private-response-token","account":{"id":"private-account","contact":{"value":"private-phone"}},"availableMinorUnits":"987654321","paymentStatus":"pending","currency":"NGN"}"""
        server.enqueue(response(200, reply))
        val request =
            Request.Builder()
                .url(
                    server.url(
                        "/api/v1/wallets/private-wallet-path/payments?currency=NGN&requestKey=private-request-key&limit=25&phoneNumber=private-query-phone",
                    ),
                )
                .header("Authorization", "Bearer private-bearer")
                .header("Cookie", "session=private-request-cookie")
                .header("X-Private-Header", "private-header-value")
                .post(body.toRequestBody(json))
                .build()
        client.newCall(request).execute().use { received ->
            assertEquals(200, received.code)
            assertEquals(reply, received.body.string())
        }
        val sent = server.takeRequest()
        assertEquals(body, sent.body!!.utf8())
        assertEquals("Bearer private-bearer", sent.headers["Authorization"])
        assertEquals(1, server.requestCount)
        val log = lines.joinToString("\n")
        assertTrue(log.contains("--> POST"))
        assertTrue(log.contains("<-- 200"))
        assertTrue(log.contains("/api/v1/wallets/<redacted>/payments"))
        assertTrue(log.contains("\"currency\":\"NGN\""))
        assertTrue(log.contains("\"serviceType\":\"data\""))
        assertTrue(log.contains("\"paymentStatus\":\"pending\""))
        assertTrue(log.contains("request headers"))
        assertTrue(log.contains("response headers"))
        assertTrue(log.contains("request body"))
        assertTrue(log.contains("response body"))
        for (secret in
            listOf("private-", "private@example.test", "001234", "123456789", "987654321")) {
            assertFalse("A private field escaped redaction", log.contains(secret))
        }
    }

    /**
     * The generated header, log prefix and echoed error reference remain the same across one
     * exchange.
     */
    @Test
    fun logsTheSameSafeRequestIdAsTheApi() {
        val id = "samsung-SM-A556E-1700000000000-550e8400-e29b-41d4-a716-446655440000"
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Request-Id", id)
                .body("{\"requestId\":\"$id\",\"password\":\"private-password\"}")
                .build(),
        )
        client
            .newCall(
                Request.Builder()
                    .url(server.url("/api/v1/auth/me"))
                    .header("X-Request-Id", id)
                    .build(),
            )
            .execute()
            .close()
        assertTrue(lines.all { it.startsWith("[$id]") })
        assertTrue(lines.any { it.contains("\"requestId\":\"$id\"") })
        assertFalse(lines.any { it.contains("private-password") })
        assertEquals(id, server.takeRequest().headers["X-Request-Id"])
    }

    /** Logging failure cannot fail or replay a real request. */
    @Test
    fun failingLogSinkDoesNotChangeTheHttpOutcome() {
        val observed =
            client
                .newBuilder()
                .apply { interceptors().clear() }
                .addInterceptor(
                    ApiHttpLoggingInterceptor { throw IllegalStateException("logger unavailable") },
                )
                .build()
        server.enqueue(response(201, "{\"currency\":\"NGN\"}"))
        observed
            .newCall(Request.Builder().url(server.url("/api/v1/payments/quotes")).build())
            .execute()
            .use {
                assertEquals(201, it.code)
                assertEquals("{\"currency\":\"NGN\"}", it.body.string())
            }
        assertEquals(1, server.requestCount)
    }

    /** Malformed JSON may contain secrets and must never be printed as a fallback body. */
    @Test
    fun malformedResponseIsOmittedAndRemainsReadableToTheCaller() {
        val raw = "not JSON: private-response-diagnostics"
        server.enqueue(response(503, raw))
        client.newCall(Request.Builder().url(server.url("/api/v1/auth/me")).build()).execute().use {
            assertEquals(503, it.code)
            assertEquals(raw, it.body.string())
        }
        assertTrue(lines.any { it.contains("<body omitted>") })
        assertFalse(lines.any { it.contains("private-response-diagnostics") })
    }

    /** Previewing diagnostics never consumes a one-shot upload before the real network write. */
    @Test
    fun oneShotRequestBodyIsWrittenOnlyOnce() {
        var writes = 0
        val upload =
            object : RequestBody() {
                /** Identify the explicit streaming body contract. */
                override fun contentType() = json

                /** Prevent speculative reads of this body. */
                override fun isOneShot() = true

                /** Track the one permitted network serialization. */
                override fun writeTo(sink: BufferedSink) {
                    writes++
                    sink.writeUtf8("{\"password\":\"private-upload\"}")
                }
            }
        server.enqueue(response(200, "{}"))
        client
            .newCall(Request.Builder().url(server.url("/api/v1/auth/signup")).post(upload).build())
            .execute()
            .close()
        assertEquals(1, writes)
        assertEquals("{\"password\":\"private-upload\"}", server.takeRequest().body!!.utf8())
        assertFalse(lines.any { it.contains("private-upload") })
        assertTrue(lines.any { it.contains("request body") && it.contains("<body omitted>") })
    }

    /** Body size limits preserve the complete wire exchange and omit oversized diagnostics. */
    @Test
    fun oversizedBodiesAreOmittedWithoutTruncatingTheExchange() {
        val body = "{\"password\":\"" + "x".repeat(70000) + "\"}"
        server.enqueue(response(200, body))
        client
            .newCall(
                Request.Builder()
                    .url(server.url("/api/v1/auth/signup"))
                    .post(body.toRequestBody(json))
                    .build(),
            )
            .execute()
            .use {
                assertEquals(body, it.body.string())
            }
        assertEquals(body, server.takeRequest().body!!.utf8())
        assertTrue(lines.any { it.contains("request body") && it.contains("<body omitted>") })
        assertTrue(lines.any { it.contains("response body") && it.contains("<body omitted>") })
        assertTrue(lines.all { it.length < 3000 })
    }

    /** Excessive JSON nesting is skipped before parsing and cannot fail a successful exchange. */
    @Test
    fun deeplyNestedJsonIsOmittedWithoutParsingItRecursively() {
        val body = "[".repeat(1000) + "\"private-deep-value\"" + "]".repeat(1000)
        server.enqueue(response(200, body))
        client
            .newCall(Request.Builder().url(server.url("/api/v1/payments")).build())
            .execute()
            .use {
                assertEquals(body, it.body.string())
            }
        assertTrue(lines.any { it.contains("response body") && it.contains("<body omitted>") })
        assertFalse(lines.any { it.contains("private-deep-value") })
    }

    /** Network exceptions preserve their identity while logs omit private exception messages. */
    @Test
    fun networkFailuresAreLoggedWithoutExposingTheirMessage() {
        val failure = IOException("private-network-detail")
        val failing = client.newBuilder().addInterceptor { throw failure }.build()
        try {
            failing.newCall(Request.Builder().url(server.url("/api/v1/auth/me")).build()).execute()
            fail()
        } catch (received: IOException) {
            assertSame(failure, received)
        }
        assertTrue(lines.any { it.contains("HTTP failed") && it.contains("IOException") })
        assertFalse(lines.any { it.contains("private-network-detail") })
        assertEquals(0, server.requestCount)
    }
}
