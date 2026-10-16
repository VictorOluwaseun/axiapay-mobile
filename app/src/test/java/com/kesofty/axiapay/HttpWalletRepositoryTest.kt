package com.kesofty.axiapay

import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.data.wallet.HttpWalletRepository
import com.kesofty.axiapay.domain.wallet.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Exercises the actual wallet HTTP adapter with isolated responses, never live credentials or
 * money.
 */
class HttpWalletRepositoryTest {
    private val server = MockWebServer()
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    private lateinit var repository: HttpWalletRepository
    private val id = "123e4567-e89b-42d3-a456-426614174001"
    private val body =
        """{"id":"$id","currency":"NGN","walletStatus":"active","availableMinorUnits":"9007199254740993","reservedMinorUnits":"100","totalMinorUnits":"9007199254741093"}"""

    /** Bind the same safe transport used by the production Hilt graph to a loopback fixture. */
    @Before
    fun start() {
        server.start()
        repository =
            HttpWalletRepository(
                ApiHttpClient(server.url("/v1/").toString(), true, client, Dispatchers.IO),
            )
    }

    /** Release every owned call and socket even if an assertion fails. */
    @After
    fun close() {
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /** Match the controller's JSON response and explicit status. */
    private fun response(status: Int, content: String = body) =
        MockResponse.Builder()
            .code(status)
            .setHeader("Content-Type", "application/json")
            .body(content)
            .build()

    /**
     * Proves current-account scoping, token propagation and exact values beyond JavaScript's safe
     * integer range.
     */
    @Test
    fun currentWalletUsesOnlyTheAuthenticatedGetContract() = runTest {
        server.enqueue(response(200))
        val wallet = repository.currentWallet("synthetic-token", "NGN")
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/wallets/me?currency=NGN", request.target)
        assertEquals("Bearer synthetic-token", request.headers["Authorization"])
        assertEquals("no-store", request.headers["Cache-Control"])
        assertEquals(id, wallet.id)
        assertEquals("9007199254740993", wallet.availableMinorUnits.toString())
        assertEquals("100", wallet.reservedMinorUnits.toString())
        assertFalse(wallet.toString().contains("9007199254740993"))
        assertEquals(1, server.requestCount)
    }

    /**
     * A missing wallet never triggers create, and invalid credentials remain distinct from
     * transient display failures.
     */
    @Test
    fun failuresNeverCreateWalletsReplayRequestsOrExposeBodies() = runTest {
        for ((status, issue) in
            listOf(
                404 to WalletIssue.Missing,
                401 to WalletIssue.InvalidSession,
                503 to WalletIssue.Unavailable,
                429 to WalletIssue.Unavailable,
            )) {
            server.enqueue(response(status, "private upstream response"))
            try {
                repository.currentWallet("secret-token", "NGN")
                fail("Expected failure")
            } catch (failure: WalletFailure) {
                assertEquals(issue, failure.issue)
                assertFalse(failure.toString().contains("private"))
                assertFalse(failure.toString().contains("secret-token"))
            }
            assertEquals("GET", server.takeRequest().method)
        }
        assertEquals(4, server.requestCount)
    }

    /**
     * Reject implicit numeric conversion, inconsistent amounts, wrong currencies and malformed
     * server identities.
     */
    @Test
    fun malformedSummariesCannotBecomeDisplayBalances() = runTest {
        val invalid =
            listOf(
                JSONObject(body).put("availableMinorUnits", 123),
                JSONObject(body).put("availableMinorUnits", "01"),
                JSONObject(body).put("reservedMinorUnits", "-1"),
                JSONObject(body).put("totalMinorUnits", "100"),
                JSONObject(body).put("currency", "USD"),
                JSONObject(body).put("id", "invalid"),
                JSONObject(body).put("walletStatus", "unknown"),
                JSONObject(body).put("totalMinorUnits", JSONObject.NULL),
            )
        for (json in invalid) {
            server.enqueue(response(200, json.toString()))
            try {
                repository.currentWallet("token", "NGN")
                fail("Malformed response")
            } catch (failure: WalletFailure) {
                assertEquals(WalletIssue.InvalidResponse, failure.issue)
            }
            server.takeRequest()
        }
    }

    /** Public cleartext and redirects cannot leak a bearer to another origin or path. */
    @Test
    fun unsafeConfigurationAndRedirectsFailClosed() = runTest {
        val invalid =
            HttpWalletRepository(
                ApiHttpClient("http://public.example/v1/", true, client, Dispatchers.IO),
            )
        try {
            invalid.currentWallet("private-token", "NGN")
            fail()
        } catch (failure: WalletFailure) {
            assertEquals(WalletIssue.Configuration, failure.issue)
        }
        assertEquals(0, server.requestCount)
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .setHeader("Location", server.url("/other").toString())
                .build(),
        )
        try {
            repository.currentWallet("token", "NGN")
            fail()
        } catch (failure: WalletFailure) {
            assertEquals(WalletIssue.Unavailable, failure.issue)
        }
        assertEquals(1, server.requestCount)
    }

    /**
     * Cancellation closes an in-flight read rather than reporting a network error or publishing a
     * late wallet.
     */
    @Test
    fun cancellationPropagatesFromTheHttpRead() = runTest {
        val bodyStarted = CompletableDeferred<Unit>()
        val callCancelled = CompletableDeferred<Unit>()
        val observedClient =
            client
                .newBuilder()
                .callTimeout(15, TimeUnit.SECONDS)
                .eventListener(
                    object : EventListener() {
                        /** Signal the body-read boundary rather than racing header delivery. */
                        override fun responseBodyStart(call: Call) {
                            bodyStarted.complete(Unit)
                        }

                        /** Verify cancellation reaches the socket-owning call. */
                        override fun canceled(call: Call) {
                            callCancelled.complete(Unit)
                        }
                    },
                )
                .build()
        val observedRepository =
            HttpWalletRepository(
                ApiHttpClient(server.url("/v1/").toString(), true, observedClient, Dispatchers.IO),
            )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body(body)
                .throttleBody(1, 10, TimeUnit.SECONDS)
                .build(),
        )
        val pending =
            async(Dispatchers.Default) { observedRepository.currentWallet("token", "NGN") }
        withContext(Dispatchers.Default) {
            withTimeout(5000) { bodyStarted.await() }
            withTimeout(2000) {
                pending.cancelAndJoin()
                callCancelled.await()
            }
        }
        assertTrue(pending.isCancelled)
        assertEquals(1, server.requestCount)
    }
}
