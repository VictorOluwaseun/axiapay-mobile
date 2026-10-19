package com.kesofty.axiapay

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.auth.*
import com.kesofty.axiapay.data.http.*
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.auth.*
import java.io.File
import java.security.KeyStore
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/**
 * Real Keystore and real API rotation on an explicitly selected phone, using isolated test
 * credentials.
 */
class SessionRenewalDeviceTest {
    /**
     * The opt-in API fixture uses disposable PostgreSQL with a restricted role. No app credential,
     * Firebase setting, wallet or payment belonging to the user is changed by this test.
     */
    @Test
    fun realApiRotationPersistsAndReplayRemovesAccess() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(
            BuildConfig.FLAVOR == "dev" && arguments.getString("runLocalRefresh") == "true",
        )
        val baseUrl = requireNotNull(arguments.getString("refreshApiBaseUrl"))
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "refresh-test-" + UUID.randomUUID()).apply { mkdirs() }
        val alias = base.packageName + ".refresh-test." + UUID.randomUUID()
        val isolated =
            object : ContextWrapper(base) {
                override fun getNoBackupFilesDir(): File = directory

                override fun getPackageName(): String = alias
            }
        val refreshCalls = AtomicInteger()
        val client =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .callTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    if (chain.request().url.encodedPath.endsWith("/auth/refresh"))
                        refreshCalls.incrementAndGet()
                    chain.proceed(chain.request())
                }
                .build()
        try {
            val clock = Clock.systemUTC()
            val raw = ApiHttpClient(baseUrl, true, client, Dispatchers.IO)
            val authentication = HttpAuthenticationRepository(raw, clock)
            val session =
                authentication.loginWithPassword(
                    AccountContact(AuthContactType.Email, "refresh-device@example.test"),
                    "test-only-mobile-refresh-password",
                )
            assertNotNull(session.refreshCredentials)
            val initial =
                StoredAuthenticationSession(
                    session.accessToken,
                    Instant.now().minusSeconds(1),
                    raw.apiIdentity,
                    session.account.id,
                    session.refreshCredentials,
                )
            val store = KeystoreAuthenticationSessionStore(isolated, Dispatchers.IO)
            store.write(initial)
            val authorized =
                raw.withSessionTokens(AuthenticationSessionRepositoryImpl(raw, store, clock))
            val identities = coroutineScope {
                (1..12)
                    .map {
                        async { authorized.request("auth/me", 200, bearer = initial.accessToken) }
                    }
                    .awaitAll()
            }
            assertTrue(identities.all { it!!.getString("id") == session.account.id })
            assertEquals(1, refreshCalls.get())
            val renewed = requireNotNull(store.read())
            assertNotEquals(initial.accessToken, renewed.accessToken)
            assertNotEquals(initial.refreshCredentials!!.token, renewed.refreshCredentials!!.token)
            assertEquals(
                initial.refreshCredentials!!.sessionId,
                renewed.refreshCredentials!!.sessionId,
            )
            assertEquals(
                renewed,
                KeystoreAuthenticationSessionStore(isolated, Dispatchers.IO).read(),
            )
            val encrypted =
                File(directory, "authentication/session.v1")
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
            assertFalse(encrypted.contains(renewed.accessToken))
            assertFalse(encrypted.contains(renewed.refreshCredentials!!.token))
            expectUnauthorized { raw.request("auth/me", 200, bearer = initial.accessToken) }
            expectUnauthorized {
                raw.request(
                    "auth/refresh",
                    200,
                    JSONObject().put("refreshToken", initial.refreshCredentials!!.token),
                )
            }
            expectUnauthorized { authorized.request("auth/me", 200, bearer = renewed.accessToken) }
            assertNull(store.read())
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            directory.deleteRecursively()
            removeTestKey(alias)
        }
    }

    /** Reject only the documented auth outcome; no token or response body enters assertion text. */
    private suspend fun expectUnauthorized(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected an authentication rejection")
        } catch (failure: ApiRequestFailure) {
            assertEquals(401, failure.status)
            assertEquals("INVALID_CREDENTIALS", failure.code)
        }
    }

    /** Remove only this fixture's unique Keystore alias. */
    private fun removeTestKey(alias: String) {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias + ".authentication.v1")
        }
    }
}
