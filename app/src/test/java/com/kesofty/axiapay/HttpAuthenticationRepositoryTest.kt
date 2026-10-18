package com.kesofty.axiapay

import com.kesofty.axiapay.data.auth.HttpAuthenticationRepository
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.auth.*
import java.time.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Exercises exact HTTP payloads, generic failure mapping, cancellation and bounded response
 * decoding.
 */
class HttpAuthenticationRepositoryTest {
    private val server = MockWebServer()
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val contact = AccountContact(AuthContactType.Email, "ada+tag@example.test")
    private val id = "123e4567-e89b-42d3-a456-426614174000"
    private lateinit var repository: HttpAuthenticationRepository
    private val challengeJson =
        """{"challengeId":"$id","expiresAt":"2026-10-16T12:05:00Z","expiresInSeconds":300,"resendAfterSeconds":60}"""
    private val accountJson =
        """{"id":"$id","contact":{"kind":"email","value":"ada+tag@example.test"},"verifiedAt":"2026-10-16T12:00:00Z"}"""
    private val sessionJson =
        """{"accessToken":"${"a".repeat(43)}","tokenType":"Bearer","expiresInSeconds":86400,"account":$accountJson}"""

    /** Starts an isolated loopback server; no test ever calls a real customer endpoint. */
    @Before
    fun start() {
        server.start()
        repository =
            HttpAuthenticationRepository(
                server.url("/v1/").toString(),
                true,
                client,
                Dispatchers.IO,
                Clock.fixed(now, ZoneOffset.UTC),
            )
    }

    /** Closes all owned sockets after success or failure. */
    @After
    fun close() {
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /**
     * A policy rejection remains distinct from credentials and can trigger a read-only config
     * refresh.
     */
    @Test
    fun disabledAuthenticationMethodHasAnExplicitFailure() = runTest {
        server.enqueue(response(403, """{"code":"AUTHENTICATION_METHOD_DISABLED"}"""))
        try {
            repository.loginWithPassword(contact, "password")
            fail("Expected policy rejection")
        } catch (failure: AuthenticationFailure) {
            assertEquals(AccountAccessIssue.MethodDisabled, failure.issue)
        }
    }

    /** Supplies the same JSON media type as the Nest controller. */
    private fun response(status: Int, body: String) =
        MockResponse.Builder()
            .code(status)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()

    /**
     * Signup/code requests have only allowed fields and cannot send UI names or hidden passwords.
     */
    @Test
    fun codeRequestsMatchThePublicContactAndPurposeContract() = runTest {
        for (purpose in AuthenticationPurpose.entries) {
            server.enqueue(response(202, challengeJson))
            val terms =
                repository.requestCode(
                    contact,
                    purpose,
                    if (purpose == AuthenticationPurpose.Signup) " exact passphrase " else null,
                )
            assertEquals(id, terms.id)
            val request = server.takeRequest()
            assertEquals(
                if (purpose == AuthenticationPurpose.Signup) "/v1/auth/signup"
                else "/v1/auth/login/code",
                request.target,
            )
            assertEquals("POST", request.method)
            assertEquals("no-store", request.headers["Cache-Control"])
            assertNull(request.headers["Authorization"])
            val payload = JSONObject(requireNotNull(request.body).utf8())
            assertEquals("email", payload.getJSONObject("contact").getString("kind"))
            assertEquals(contact.value, payload.getJSONObject("contact").getString("value"))
            assertEquals(purpose == AuthenticationPurpose.Signup, payload.has("password"))
            assertFalse(payload.has("name"))
        }
    }

    /**
     * Verification preserves leading zeros, uses its challenge's endpoint and maps bearer sessions.
     */
    @Test
    fun verificationPasswordLoginIdentityAndLogoutUseExactContracts() = runTest {
        for (purpose in AuthenticationPurpose.entries) {
            server.enqueue(
                response(if (purpose == AuthenticationPurpose.Signup) 201 else 200, sessionJson),
            )
            val challenge =
                AuthenticationChallenge(id, purpose, contact, true, now.plusSeconds(300), now)
            val session = repository.verifyCode(challenge, "001234")
            assertEquals(now.plusSeconds(86400), session.expiresAt)
            val request = server.takeRequest()
            assertEquals(
                if (purpose == AuthenticationPurpose.Signup) "/v1/auth/signup/verify"
                else "/v1/auth/login/code/verify",
                request.target,
            )
            val body = JSONObject(requireNotNull(request.body).utf8())
            assertEquals(id, body.getString("challengeId"))
            assertEquals("001234", body.getString("code"))
        }
        server.enqueue(response(200, sessionJson))
        repository.loginWithPassword(contact, " exact password ")
        assertEquals(
            " exact password ",
            JSONObject(requireNotNull(server.takeRequest().body).utf8()).getString("password"),
        )
        server.enqueue(response(200, accountJson))
        repository.currentAccount("a".repeat(43))
        val me = server.takeRequest()
        assertEquals("GET", me.method)
        assertEquals("/v1/auth/me", me.target)
        assertEquals("Bearer ${"a".repeat(43)}", me.headers["Authorization"])
        server.enqueue(MockResponse.Builder().code(204).build())
        repository.logout("a".repeat(43))
        val logout = server.takeRequest()
        assertEquals("POST", logout.method)
        assertEquals("/v1/auth/logout", logout.target)
    }

    /**
     * Safe failure classes never include provider text or allow failed auth to become demo access.
     */
    @Test
    fun rateLimitsCredentialsServerFailuresAndMalformedResponsesRemainExplicit() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .setHeader("Retry-After", "73")
                .setHeader("X-Request-Id", "safe-request-reference")
                .body("sensitive response body")
                .build(),
        )
        try {
            repository.loginWithPassword(contact, "secret")
            fail()
        } catch (failure: AuthenticationFailure) {
            assertEquals(AccountAccessIssue.RateLimited, failure.issue)
            assertEquals(73, failure.retryAfterSeconds)
            assertEquals("safe-request-reference", failure.requestReference)
            assertFalse(failure.toString().contains("sensitive"))
        }
        server.takeRequest()
        for (status in listOf(400, 401, 503)) {
            server.enqueue(response(status, "{}"))
            try {
                repository.loginWithPassword(contact, "secret")
                fail()
            } catch (failure: AuthenticationFailure) {
                assertEquals(
                    when (status) {
                        400 -> AccountAccessIssue.InvalidRequest
                        401 -> AccountAccessIssue.InvalidCredentials
                        else -> AccountAccessIssue.Unavailable
                    },
                    failure.issue,
                )
            }
            server.takeRequest()
        }
        for (body in
            listOf(
                challengeJson.replace("300", "\"300\""),
                challengeJson.replace(id, "not-a-uuid"),
                "{}",
                "{" + "x".repeat(65537),
            )) {
            server.enqueue(response(202, body))
            try {
                repository.requestCode(contact, AuthenticationPurpose.Login, null)
                fail()
            } catch (failure: AuthenticationFailure) {
                assertEquals(AccountAccessIssue.InvalidResponse, failure.issue)
            }
            server.takeRequest()
        }
    }

    /** Auth responses cannot redirect exact credentials to another origin. */
    @Test
    fun redirectsDoNotForwardCredentials() = runTest {
        MockWebServer().use { destination ->
            destination.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .setHeader("Location", destination.url("/capture"))
                    .build(),
            )
            try {
                repository.loginWithPassword(contact, "private password")
                fail()
            } catch (failure: AuthenticationFailure) {
                assertEquals(AccountAccessIssue.Unavailable, failure.issue)
            }
            assertEquals(1, server.requestCount)
            assertEquals(0, destination.requestCount)
        }
    }

    /** Cancelling an in-flight request cancels OkHttp and never retries a code redemption. */
    @Test
    fun cancellationStopsTheCallWithoutReplay() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .headersDelay(2, TimeUnit.SECONDS)
                .body(sessionJson)
                .build(),
        )
        val request =
            launch(Dispatchers.Default) { repository.loginWithPassword(contact, "password") }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        request.cancelAndJoin()
        assertEquals(1, server.requestCount)
        assertTrue(request.isCancelled)
    }

    /**
     * Only explicitly enabled loopback HTTP is accepted; invalid configuration sends no request.
     */
    @Test
    fun cleartextAndCredentialBearingBaseUrlsAreRejected() = runTest {
        for (url in
            listOf(
                server.url("/v1/").toString(),
                "http://public.example/v1/",
                "https://user:pass@example.test/v1/",
                "https://example.test/v1/?key=bad",
                "",
            )) {
            val transport =
                HttpAuthenticationRepository(
                    url,
                    false,
                    client,
                    Dispatchers.IO,
                    Clock.fixed(now, ZoneOffset.UTC),
                )
            try {
                transport.requestCode(contact, AuthenticationPurpose.Login, null)
                fail()
            } catch (failure: AuthenticationFailure) {
                assertEquals(AccountAccessIssue.Configuration, failure.issue)
            }
        }
        assertEquals(0, server.requestCount)
    }
}
