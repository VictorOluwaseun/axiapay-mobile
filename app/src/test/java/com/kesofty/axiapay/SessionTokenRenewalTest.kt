package com.kesofty.axiapay

import com.kesofty.axiapay.data.auth.AuthenticationSessionRepositoryImpl
import com.kesofty.axiapay.data.http.*
import com.kesofty.axiapay.domain.auth.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Exercise actual suspending HTTP renewal, one-use proof coordination and atomic credential races.
 */
class SessionTokenRenewalTest {
    private val server = MockWebServer()
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    private val accountId = "c764f366-e737-4c84-b63b-0df02ad39c76"
    private val familyId = "946a98d5-246f-41d5-93c6-cf052fb10769"
    private val access = "A".repeat(43)
    private val proof = "R".repeat(43)
    private val nextAccess = "C".repeat(43)
    private lateinit var raw: ApiHttpClient
    private lateinit var http: ApiHttpClient
    private lateinit var initial: StoredAuthenticationSession
    private lateinit var store: RenewalTokenStore

    /** Use only an isolated loopback API; no real account or credential enters the suite. */
    @Before
    fun start() {
        server.start()
        raw = ApiHttpClient(server.url("/api/v1/").toString(), true, client, Dispatchers.IO)
        initial =
            StoredAuthenticationSession(
                access,
                now.minusSeconds(1),
                raw.apiIdentity,
                accountId,
                RefreshCredentials(proof, now.plusSeconds(2592000), familyId),
            )
        store = RenewalTokenStore(initial)
        http = raw.withSessionTokens(AuthenticationSessionRepositoryImpl(raw, store, clock))
    }

    /** Release this fixture's sockets and executor even when a coordination assertion fails. */
    @After
    fun close() {
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** Supply the real API response shape with distinct opaque tokens and a stable family. */
    private fun renewed(family: String = familyId): String =
        JSONObject()
            .put("accessToken", nextAccess)
            .put("refreshToken", "S".repeat(43))
            .put("sessionId", family)
            .put("tokenType", "Bearer")
            .put("expiresInSeconds", 900)
            .put("refreshExpiresInSeconds", 2592000)
            .put(
                "account",
                JSONObject()
                    .put("id", accountId)
                    .put(
                        "contact",
                        JSONObject().put("kind", "email").put("value", "fixture@example.test"),
                    )
                    .put("verifiedAt", now.toString()),
            )
            .toString()

    /** Return explicit JSON so errors traverse the production bounded error decoder. */
    private fun response(code: Int = 200, body: String = "{}"): MockResponse =
        MockResponse.Builder()
            .code(code)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()

    /**
     * All concurrent callers share one rotation and see credentials only after the whole pair
     * commits.
     */
    @Test
    fun concurrentExpiredRequestsShareOneRefresh() = runTest {
        val renewals = AtomicInteger()
        val resources = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.target == "/api/v1/auth/refresh") {
                        renewals.incrementAndGet()
                        assertNull(request.headers["Authorization"])
                        assertEquals(
                            proof,
                            JSONObject(request.body!!.utf8()).getString("refreshToken"),
                        )
                        return response(body = renewed())
                    }
                    assertEquals("Bearer $nextAccess", request.headers["Authorization"])
                    assertEquals(nextAccess, store.value!!.accessToken)
                    resources.incrementAndGet()
                    return response()
                }
            }
        (1..20)
            .map { async(Dispatchers.Default) { http.request("wallets/me", 200, bearer = access) } }
            .awaitAll()
        assertEquals(1, renewals.get())
        assertEquals(20, resources.get())
        assertEquals(1, store.replacements)
        assertEquals(familyId, store.value!!.refreshCredentials!!.sessionId)
    }

    /**
     * A rejected mutation retains its payload/key and uses one new correlation ID for the retry.
     */
    @Test
    fun authenticationRejectionRetriesTheSameMutationOnce() = runTest {
        store.write(initial.copy(expiresAt = now.plusSeconds(600)))
        val bodies = mutableListOf<String>()
        val references = mutableListOf<String?>()
        val accepted = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.target.endsWith("/refresh")) {
                        return response(body = renewed())
                    }
                    bodies.add(request.body!!.utf8())
                    references.add(request.headers["X-Request-Id"])
                    if (request.headers["Authorization"] == "Bearer $access") {
                        return response(401, """{"code":"INVALID_CREDENTIALS"}""")
                    }
                    accepted.incrementAndGet()
                    return response()
                }
            }
        http.request(
            "payments",
            200,
            JSONObject().put("quoteId", accountId).put("requestKey", "original-key"),
            access,
        )
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1])
        assertEquals("original-key", JSONObject(bodies.last()).getString("requestKey"))
        assertNotEquals(references[0], references[1])
        assertEquals(1, accepted.get())
        assertEquals(3, server.requestCount)
    }

    /** Refreshed credentials rejected by the server cannot cause a retry loop or remain stored. */
    @Test
    fun repeatedUnauthorizedResponseStopsAndClearsOnlyTheRejectedPair() = runTest {
        store.write(initial.copy(expiresAt = now.plusSeconds(600)))
        server.enqueue(response(401, """{"code":"INVALID_CREDENTIALS"}"""))
        server.enqueue(response(body = renewed()))
        server.enqueue(response(401, """{"code":"INVALID_CREDENTIALS"}"""))
        expectFailure(ApiFailureKind.Http) { http.request("wallets/me", 200, bearer = access) }
        assertEquals(3, server.requestCount)
        assertNull(store.read())
    }

    /**
     * Temporary service failures preserve saved credentials and never send the protected mutation.
     */
    @Test
    fun failedRefreshRetainsTheSessionAndRecoversWithoutReplayingPayment() = runTest {
        server.enqueue(response(503, """{"code":"AUTHENTICATION_UNAVAILABLE"}"""))
        expectFailure(ApiFailureKind.Http) {
            http.request("payments", 200, JSONObject().put("requestKey", "original"), access)
        }
        assertEquals(initial, store.read())
        assertEquals(1, server.requestCount)
        server.enqueue(response(body = renewed()))
        server.enqueue(response())
        http.request("payments", 200, JSONObject().put("requestKey", "original"), access)
        assertEquals(3, server.requestCount)
    }

    /**
     * A received pair survives a temporary local commit error without consuming the old proof
     * twice.
     */
    @Test
    fun localWriteFailureRecoversTheReceivedPairBeforeResourceIo() = runTest {
        store.failWrite = true
        server.enqueue(response(body = renewed()))
        expectFailure(ApiFailureKind.Storage) {
            http.request("payments", 200, JSONObject(), access)
        }
        assertEquals(initial, store.read())
        assertEquals(1, server.requestCount)
        store.failWrite = false
        server.enqueue(response())
        http.request("payments", 200, JSONObject(), access)
        assertEquals(2, server.requestCount)
        assertEquals(nextAccess, store.read()!!.accessToken)
        assertEquals(1, store.replacements)
    }

    /** Logout and a separate login both win against a refresh that completes later. */
    @Test
    fun lateRefreshCannotResurrectLogoutOrOverwriteAnotherLogin() = runTest {
        for (logout in listOf(false, true)) {
            store.write(initial)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        entered.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                        return response(body = renewed())
                    }
                }
            val pending = async { runCatching { http.request("wallets/me", 200, bearer = access) } }
            entered.await()
            val newer =
                initial.copy(
                    accessToken = "N".repeat(43),
                    accountId = "41bead6e-75da-467e-8d21-bad5a545ab36",
                    refreshCredentials =
                        initial.refreshCredentials!!.copy(
                            sessionId = "9ad9dcf9-98bf-4971-a288-fc791db4ba76",
                        ),
                )
            if (logout) {
                store.clear(initial)
            } else {
                store.write(newer)
            }
            release.countDown()
            assertTrue(pending.await().exceptionOrNull() is ApiRequestFailure)
            assertEquals(if (logout) null else newer, store.read())
        }
        assertEquals(2, server.requestCount)
    }

    /**
     * Cancellation closes refresh IO and never publishes partial credentials or sends a resource
     * request.
     */
    @Test
    fun cancelledRenewalKeepsThePreviousPair() = runTest {
        val entered = CompletableDeferred<Unit>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.complete(Unit)
                    return MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body(renewed())
                        .bodyDelay(2, TimeUnit.SECONDS)
                        .build()
                }
            }
        val pending = launch { http.request("wallets/me", 200, bearer = access) }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(initial, store.read())
        assertEquals(1, server.requestCount)
    }

    /**
     * Neither a foreign endpoint nor an unrelated bearer can borrow the current user's refresh
     * proof.
     */
    @Test
    fun apiAndBearerIsolationRejectBeforeNetworkIo() = runTest {
        val provider = AuthenticationSessionRepositoryImpl(raw, store, clock)
        for ((identity, bearer) in
            listOf(
                "https://another.example/api/v1/" to access,
                raw.apiIdentity to "Z".repeat(43),
            )) {
            try {
                provider.accessToken(identity, bearer)
                fail("Expected credential isolation")
            } catch (failure: AuthenticationFailure) {
                assertEquals(AccountAccessIssue.InvalidCredentials, failure.issue)
            }
        }
        assertEquals(0, server.requestCount)
        assertEquals(initial, store.read())
    }

    /** A response for a different session cannot replace the proof used to request renewal. */
    @Test
    fun foreignFamilyResponseNeverReplacesLocalCredentials() = runTest {
        server.enqueue(response(body = renewed("9ad9dcf9-98bf-4971-a288-fc791db4ba76")))
        expectFailure(ApiFailureKind.InvalidResponse) {
            http.request("wallets/me", 200, bearer = access)
        }
        assertEquals(initial, store.read())
        assertEquals(1, server.requestCount)
    }

    /** Old token-only records work until expiry and require explicit sign-in afterward. */
    @Test
    fun legacyExpiredRecordsNeverInventRefreshCredentials() = runTest {
        store.write(initial.copy(refreshCredentials = null))
        expectFailure(ApiFailureKind.Http) { http.request("wallets/me", 200, bearer = access) }
        assertEquals(0, server.requestCount)
        assertNull(store.read())
    }

    /** Assert only approved metadata, never a credential-bearing transport message. */
    private suspend fun expectFailure(kind: ApiFailureKind, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected $kind")
        } catch (failure: ApiRequestFailure) {
            assertEquals(kind, failure.kind)
        }
    }
}

/**
 * A serialized atomic store models Keystore login/logout/rotation races without Android
 * dependencies.
 */
private class RenewalTokenStore(@Volatile var value: StoredAuthenticationSession?) :
    AuthenticationSessionStore {
    private val lock = Mutex()
    var failWrite = false
    var replacements = 0

    override suspend fun read() = lock.withLock { value }

    override suspend fun write(session: StoredAuthenticationSession) = lock.withLock {
        value = session
    }

    override suspend fun clear(expected: StoredAuthenticationSession) = lock.withLock {
        if (value == expected) {
            value = null
        }
    }

    override suspend fun replace(
        expected: StoredAuthenticationSession,
        replacement: StoredAuthenticationSession,
    ): Boolean = lock.withLock {
        if (value != expected) {
            return@withLock false
        }
        if (failWrite) {
            throw AuthenticationFailure(AccountAccessIssue.SecureStorage)
        }
        value = replacement
        replacements++
        true
    }
}
