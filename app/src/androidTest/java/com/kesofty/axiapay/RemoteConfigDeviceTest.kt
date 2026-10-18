package com.kesofty.axiapay

import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.application.config.LoadRemoteConfiguration
import com.kesofty.axiapay.application.config.RemoteConfigurationUpdate
import com.kesofty.axiapay.data.AtomicTextCheckpoint
import com.kesofty.axiapay.data.config.RemoteConfigRepositoryImpl
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.domain.config.PaymentFeature
import com.kesofty.axiapay.domain.config.RemoteConfigIssue
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Exercise real Android atomic files and conditional HTTP without accounts or Firebase mutations.
 */
class RemoteConfigDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "config-tests/${UUID.randomUUID()}")
    private val statuses = mutableListOf<Int>()
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request()).also { statuses.add(it.code) }
            }
            .build()

    /**
     * Only dev permits the local HTTP fixtures; other flavors retain their production network
     * policy.
     */
    @Before
    fun requireDevelopmentFlavor() {
        Assume.assumeTrue(BuildConfig.FLAVOR == "dev")
    }

    /** Release only this test's HTTP resources and unique private cache directory. */
    @After
    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        directory.deleteRecursively()
    }

    /** Use the same whole-record AtomicFile adapter and conditional repository as production. */
    private fun repository(baseUrl: String) =
        RemoteConfigRepositoryImpl(
            ApiHttpClient(baseUrl, true, client, Dispatchers.IO),
            AtomicTextCheckpoint(File(directory, "snapshot.json"), Dispatchers.IO),
        )

    /**
     * A fresh adapter instance reads the committed record and successfully handles an empty 304.
     */
    @Test
    fun atomicSnapshotSurvivesRepositoryRecreation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("ETag", "\"device-v1\"")
                    .body(
                        JSONObject(
                                """{"version":"device-v1","authentication":{"signup":{"emailEnabled":true,"phoneEnabled":false,"passwordEnabled":true,"otpEnabled":false},"login":{"emailEnabled":false,"phoneEnabled":true,"passwordEnabled":false,"otpEnabled":true}}}""",
                            )
                            .put(
                                "features",
                                JSONObject().apply {
                                    PaymentFeature.entries.forEach {
                                        put(
                                            it.configurationKey,
                                            JSONObject()
                                                .put("enabled", it != PaymentFeature.Airtime),
                                        )
                                    }
                                },
                            )
                            .toString(),
                    )
                    .build(),
            )
            val first = repository(server.url("/api/v1/").toString()).refresh()
            assertFalse(first.features.airtime)
            assertTrue(first.features.data)
            assertFalse(first.signup.phoneEnabled)
            assertFalse(first.login.emailEnabled)
            assertTrue(first.signup.passwordEnabled)
            assertFalse(first.signup.otpEnabled)
            assertFalse(first.login.passwordEnabled)
            assertTrue(first.login.otpEnabled)
            assertEquals(first, repository(server.url("/api/v1/").toString()).readCached())
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest().headers["If-None-Match"])
            server.enqueue(MockResponse.Builder().code(503).build())
            val offline =
                LoadRemoteConfiguration(repository(server.url("/api/v1/").toString()))().toList()
            assertEquals(
                listOf(
                    RemoteConfigurationUpdate.Cached(first),
                    RemoteConfigurationUpdate.Failed(RemoteConfigIssue.Unavailable),
                ),
                offline,
            )
            assertEquals("\"device-v1\"", server.takeRequest().headers["If-None-Match"])
            server.enqueue(
                MockResponse.Builder().code(304).setHeader("ETag", "\"device-v1\"").build(),
            )
            assertEquals(first, repository(server.url("/api/v1/").toString()).refresh())
            val repeated = server.takeRequest()
            assertEquals("\"device-v1\"", repeated.headers["If-None-Match"])
            assertNull(repeated.headers["Authorization"])
            assertEquals(listOf(200, 503, 304), statuses)
        } finally {
            server.close()
        }
    }

    /** Opt-in read-only smoke test through the local API and its live Firebase server template. */
    @Test
    fun localApiRevalidatesFirebaseConfiguration() = runBlocking {
        Assume.assumeTrue(
            BuildConfig.FLAVOR == "dev" &&
                InstrumentationRegistry.getArguments().getString("runLocalRemoteConfig") == "true",
        )
        val first = repository(BuildConfig.API_BASE_URL).refresh()
        assertTrue(first.version.startsWith("rc1-"))
        val repeated = repository(BuildConfig.API_BASE_URL).refresh()
        assertEquals(first, repeated)
        assertEquals(listOf(200, 304), statuses)
    }
}
