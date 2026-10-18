package com.kesofty.axiapay

import com.kesofty.axiapay.application.config.LoadRemoteConfiguration
import com.kesofty.axiapay.application.config.RemoteConfigurationUpdate
import com.kesofty.axiapay.data.config.RemoteConfigRepositoryImpl
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.domain.TextCheckpoint
import com.kesofty.axiapay.domain.config.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
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
 * Real HTTP checks for conditional reads, atomic persistence, failure recovery and API isolation.
 */
class RemoteConfigRepositoryTest {
    private val server = MockWebServer()
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    private val checkpoint = ConfigCheckpoint()
    private lateinit var http: ApiHttpClient

    /** Bind an isolated loopback API so tests cannot change the published Firebase template. */
    @Before
    fun start() {
        server.start()
        http = ApiHttpClient(server.url("/api/v1/").toString(), true, client, Dispatchers.IO)
    }

    /** Close requests, pooled sockets and server resources even after assertions fail. */
    @After
    fun close() {
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /**
     * Build exactly the documented response while independently changing each operation/contact.
     */
    private fun body(
        version: String = "v1",
        signupEmail: Boolean = true,
        signupPhone: Boolean = true,
        loginEmail: Boolean = true,
        loginPhone: Boolean = true,
        signupPassword: Boolean = true,
        signupOtp: Boolean = true,
        loginPassword: Boolean = true,
        loginOtp: Boolean = true,
    ) =
        """{"version":"$version","authentication":{"signup":{"emailEnabled":$signupEmail,"phoneEnabled":$signupPhone,"passwordEnabled":$signupPassword,"otpEnabled":$signupOtp},"login":{"emailEnabled":$loginEmail,"phoneEnabled":$loginPhone,"passwordEnabled":$loginPassword,"otpEnabled":$loginOtp}}}"""

    /** Enqueue one complete versioned JSON snapshot with its paired HTTP validator. */
    private fun enqueue(value: String = body(), version: String = "v1") {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("ETag", "\"$version\"")
                .body(value)
                .build(),
        )
    }

    /**
     * A new repository restores the body/version together, then revalidates without parsing 304
     * JSON.
     */
    @Test
    fun persistedSnapshotRevalidatesAcrossRepositoryRecreation() = runTest {
        enqueue(body(signupPhone = false, loginEmail = false))
        val first = RemoteConfigRepositoryImpl(http, checkpoint).refresh()
        val request = server.takeRequest()
        assertEquals("/api/v1/config", request.target)
        assertNull(request.headers["If-None-Match"])
        assertNull(request.headers["Authorization"])
        assertFalse(first.signup.phoneEnabled)
        assertFalse(first.login.emailEnabled)
        server.enqueue(MockResponse.Builder().code(304).setHeader("ETag", "\"v1\"").build())
        assertEquals(first, RemoteConfigRepositoryImpl(http, checkpoint).refresh())
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
        assertEquals(1, checkpoint.writes)
    }

    /** Repository recreation restores disabled policy without issuing an HTTP request. */
    @Test
    fun cachedReadsRestoreTheCompletePolicyWithoutNetworkIo() = runTest {
        enqueue(body(signupEmail = false, loginEmail = false, loginPhone = false))
        val saved = RemoteConfigRepositoryImpl(http, checkpoint).refresh()
        server.takeRequest()
        val restored = RemoteConfigRepositoryImpl(http, checkpoint)
        assertEquals(saved, restored.readCached())
        assertEquals(saved, restored.readCached())
        assertEquals(AuthenticationMethods(false, false), restored.readCached()!!.login)
        assertEquals(1, server.requestCount)
        assertEquals(1, checkpoint.writes)
    }

    /** Cached display reads cannot queue behind a suspended online revalidation. */
    @Test
    fun cacheHitsRemainAvailableDuringAnInFlightRefresh() = runTest {
        enqueue()
        val saved = RemoteConfigRepositoryImpl(http, checkpoint).refresh()
        server.takeRequest()
        val enteredNetwork = CompletableDeferred<Unit>()
        val releaseNetwork = CompletableDeferred<Unit>()
        val gatedClient =
            client
                .newBuilder()
                .addInterceptor { chain ->
                    enteredNetwork.complete(Unit)
                    runBlocking { releaseNetwork.await() }
                    chain.proceed(chain.request())
                }
                .build()
        val repository =
            RemoteConfigRepositoryImpl(
                ApiHttpClient(server.url("/api/v1/").toString(), true, gatedClient, Dispatchers.IO),
                checkpoint,
            )
        server.enqueue(MockResponse.Builder().code(304).setHeader("ETag", "\"v1\"").build())
        val refresh = async(Dispatchers.Default) { repository.refresh() }
        try {
            withContext(Dispatchers.Default) { withTimeout(3000) { enteredNetwork.await() } }
            val local =
                withContext(Dispatchers.Default) { withTimeout(1000) { repository.readCached() } }
            assertEquals(saved, local)
            assertTrue(refresh.isActive)
        } finally {
            releaseNetwork.complete(Unit)
            refresh.cancelAndJoin()
        }
    }

    /** Temporary read failures are cache misses, not permanent loss of saved offline policy. */
    @Test
    fun localStorageReadCanRecoverWithoutAnyNetworkRequest() = runTest {
        enqueue(body(loginEmail = false))
        val saved = RemoteConfigRepositoryImpl(http, checkpoint).refresh()
        server.takeRequest()
        val restored = RemoteConfigRepositoryImpl(http, checkpoint)
        checkpoint.failRead = true
        assertNull(restored.readCached())
        checkpoint.failRead = false
        assertEquals(saved, restored.readCached())
        assertEquals(1, server.requestCount)
    }

    /**
     * An offline restart emits saved switches first; recovery persists the new body/version
     * together.
     */
    @Test
    fun offlineFirstLoadingRetainsSavedPolicyThenRecoversWithTheOriginalValidator() = runTest {
        enqueue(body(loginEmail = false))
        val saved = RemoteConfigRepositoryImpl(http, checkpoint).refresh()
        server.takeRequest()
        val restored = RemoteConfigRepositoryImpl(http, checkpoint)
        server.enqueue(MockResponse.Builder().code(503).build())
        val offline = LoadRemoteConfiguration(restored)().toList()
        assertEquals(
            listOf(
                RemoteConfigurationUpdate.Cached(saved),
                RemoteConfigurationUpdate.Failed(RemoteConfigIssue.Unavailable),
            ),
            offline,
        )
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
        assertEquals(saved, restored.readCached())
        enqueue(body("v2", false, false, false, false), "v2")
        val recovered = LoadRemoteConfiguration(restored)().toList()
        assertEquals(RemoteConfigurationUpdate.Cached(saved), recovered[0])
        val updated = (recovered[1] as RemoteConfigurationUpdate.Refreshed).configuration
        assertEquals("v2", updated.version)
        assertEquals(AuthenticationMethods(false, false), updated.login)
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
        assertEquals(updated, RemoteConfigRepositoryImpl(http, checkpoint).readCached())
    }

    /**
     * Changed configuration is persisted as a complete record; current policy has no enabled
     * defaults.
     */
    @Test
    fun updatedVersionReplacesAllEightAuthenticationSwitchesAtomically() = runTest {
        val repository = RemoteConfigRepositoryImpl(http, checkpoint)
        enqueue()
        repository.refresh()
        server.takeRequest()
        enqueue(body("v2", false, false, false, false, false, false, false, false), "v2")
        val updated = repository.refresh()
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
        assertEquals(AuthenticationMethods(false, false, false, false), updated.signup)
        assertEquals(AuthenticationMethods(false, false, false, false), updated.login)
        assertEquals(
            "v2",
            JSONObject(checkpoint.value!!).getJSONObject("configuration").getString("version"),
        )
    }

    /**
     * Legacy snapshots preserve contact policy while retaining the pre-existing credential choices.
     */
    @Test
    fun legacyContactOnlySnapshotRetainsBothCredentialMethods() = runTest {
        val legacy = JSONObject(body("legacy", signupPhone = false, loginEmail = false))
        for (operation in listOf("signup", "login")) {
            legacy.getJSONObject("authentication").getJSONObject(operation).apply {
                remove("passwordEnabled")
                remove("otpEnabled")
            }
        }
        enqueue(legacy.toString(), "legacy")
        val saved = RemoteConfigRepositoryImpl(http, checkpoint).refresh()
        server.takeRequest()
        assertFalse(saved.signup.phoneEnabled)
        assertFalse(saved.login.emailEnabled)
        assertTrue(saved.signup.passwordEnabled && saved.signup.otpEnabled)
        assertTrue(saved.login.passwordEnabled && saved.login.otpEnabled)
        val restored = RemoteConfigRepositoryImpl(http, checkpoint)
        assertEquals(saved, restored.readCached())
        server.enqueue(MockResponse.Builder().code(304).setHeader("ETag", "\"legacy\"").build())
        assertEquals(saved, restored.refresh())
        assertEquals("\"legacy\"", server.takeRequest().headers["If-None-Match"])
    }

    /** New credential booleans survive storage, outages and conditional refresh independently. */
    @Test
    fun credentialSwitchesPersistOfflineWithoutEnablingDisabledMethods() = runTest {
        for (operation in listOf("signup", "login")) {
            for (field in listOf("passwordEnabled", "otpEnabled")) {
                val version = "$operation-$field"
                val changed = JSONObject(body(version))
                changed.getJSONObject("authentication").getJSONObject(operation).put(field, false)
                enqueue(changed.toString(), version)
                val saved = RemoteConfigRepositoryImpl(http, checkpoint).refresh()
                server.takeRequest()
                val expected =
                    AuthenticationMethods(
                        true,
                        true,
                        field != "passwordEnabled",
                        field != "otpEnabled",
                    )
                assertEquals(expected, if (operation == "signup") saved.signup else saved.login)
                val restored = RemoteConfigRepositoryImpl(http, checkpoint)
                assertEquals(saved, restored.readCached())
                server.enqueue(MockResponse.Builder().code(503).build())
                expectFailure(RemoteConfigIssue.Unavailable) { restored.refresh() }
                server.takeRequest()
                assertEquals(saved, restored.readCached())
            }
        }
    }

    /**
     * Missing one new field or malformed booleans cannot replace known disabled credential policy.
     */
    @Test
    fun partialCredentialPoliciesNeverOverwriteTheSavedSnapshot() = runTest {
        val repository = RemoteConfigRepositoryImpl(http, checkpoint)
        enqueue(body(signupPassword = false, loginOtp = false))
        val saved = repository.refresh()
        server.takeRequest()
        val original = checkpoint.value
        for (operation in listOf("signup", "login")) {
            val partiallyLegacy = JSONObject(body("invalid"))
            partiallyLegacy.getJSONObject("authentication").getJSONObject(operation).apply {
                remove("passwordEnabled")
                remove("otpEnabled")
            }
            enqueue(partiallyLegacy.toString(), "invalid")
            expectFailure(RemoteConfigIssue.InvalidResponse) { repository.refresh() }
            server.takeRequest()
            assertEquals(original, checkpoint.value)
            assertEquals(saved, repository.readCached())
            for (field in listOf("passwordEnabled", "otpEnabled")) {
                for (invalid in listOf(null, "false", JSONObject.NULL)) {
                    val malformed = JSONObject(body("invalid"))
                    val methods = malformed.getJSONObject("authentication").getJSONObject(operation)
                    when (invalid) {
                        null -> methods.remove(field)
                        else -> methods.put(field, invalid)
                    }
                    enqueue(malformed.toString(), "invalid")
                    expectFailure(RemoteConfigIssue.InvalidResponse) { repository.refresh() }
                    server.takeRequest()
                    assertEquals(original, checkpoint.value)
                    assertEquals(saved, repository.readCached())
                }
            }
        }
    }

    /**
     * Unexpected 304, invalid booleans and missing/mismatched validators cannot enable a method.
     */
    @Test
    fun malformedResponsesNeverBecomeUsableOrPersistent() = runTest {
        server.enqueue(MockResponse.Builder().code(304).setHeader("ETag", "\"v1\"").build())
        expectFailure(RemoteConfigIssue.InvalidResponse) {
            RemoteConfigRepositoryImpl(http, checkpoint).refresh()
        }
        server.takeRequest()
        for (invalid in
            listOf(
                "{}",
                body().replace("true", "\"true\""),
                body().replace("emailEnabled", "missingEmail"),
            )) {
            enqueue(invalid)
            expectFailure(RemoteConfigIssue.InvalidResponse) {
                RemoteConfigRepositoryImpl(http, checkpoint).refresh()
            }
            server.takeRequest()
        }
        enqueue(version = "wrong")
        expectFailure(RemoteConfigIssue.InvalidResponse) {
            RemoteConfigRepositoryImpl(http, checkpoint).refresh()
        }
        server.takeRequest()
        assertNull(checkpoint.value)
    }

    /**
     * Cache corruption and an API-base change cause unconditional fetches instead of
     * cross-environment 304.
     */
    @Test
    fun corruptOrForeignSnapshotsDoNotSupplyValidators() = runTest {
        for (stored in
            listOf(
                "broken",
                """{"apiIdentity":"https://another.example/api/v1/","configuration":${body()}}""",
            )) {
            checkpoint.value = stored
            assertNull(RemoteConfigRepositoryImpl(http, checkpoint).readCached())
            enqueue()
            RemoteConfigRepositoryImpl(http, checkpoint).refresh()
            assertNull(server.takeRequest().headers["If-None-Match"])
        }
    }

    /**
     * Failed replacement retains the previous atomic record and validator rather than publishing
     * the new one.
     */
    @Test
    fun failedPersistenceRetainsPreviousVersion() = runTest {
        val repository = RemoteConfigRepositoryImpl(http, checkpoint)
        enqueue()
        val original = repository.refresh()
        server.takeRequest()
        val stored = checkpoint.value
        checkpoint.failWrite = true
        enqueue(body("v2", false), "v2")
        expectFailure(RemoteConfigIssue.Storage) { repository.refresh() }
        server.takeRequest()
        assertEquals(stored, checkpoint.value)
        assertEquals(original, repository.readCached())
        assertEquals(original, RemoteConfigRepositoryImpl(http, checkpoint).readCached())
        server.enqueue(MockResponse.Builder().code(304).setHeader("ETag", "\"v1\"").build())
        assertEquals(original, repository.refresh())
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
    }

    /**
     * An outage never returns old availability as a successful refresh; recovery keeps its
     * validator.
     */
    @Test
    fun unavailableRefreshFailsThenRecoversWithSavedVersion() = runTest {
        val repository = RemoteConfigRepositoryImpl(http, checkpoint)
        enqueue()
        val original = repository.refresh()
        server.takeRequest()
        server.enqueue(
            MockResponse.Builder().code(503).body("sensitive provider diagnostics").build(),
        )
        expectFailure(RemoteConfigIssue.Unavailable) { repository.refresh() }
        server.takeRequest()
        server.enqueue(MockResponse.Builder().code(304).setHeader("ETag", "\"v1\"").build())
        assertEquals(original, repository.refresh())
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
    }

    /** Cancellation exits the owner without publishing a partial or late network response. */
    @Test
    fun cancelledRefreshDoesNotPersistLateBody() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("ETag", "\"v1\"")
                .body(body())
                .bodyDelay(2, TimeUnit.SECONDS)
                .build(),
        )
        val action =
            launch(Dispatchers.Default) { RemoteConfigRepositoryImpl(http, checkpoint).refresh() }
        withContext(Dispatchers.IO) { server.takeRequest() }
        action.cancelAndJoin()
        assertTrue(action.isCancelled)
        assertNull(checkpoint.value)
    }

    /**
     * Every flag survives disk recreation and outage; malformed replacement cannot partially enable
     * a feature.
     */
    @Test
    fun independentFeatureSwitchesPersistOfflineAndRevalidate() = runTest {
        fun withFeatures(version: String, disabled: PaymentFeature? = null): String {
            val features = JSONObject()
            PaymentFeature.entries.forEach {
                features.put(it.configurationKey, JSONObject().put("enabled", it != disabled))
            }
            return JSONObject(body(version)).put("features", features).toString()
        }
        for (feature in PaymentFeature.entries) {
            val version = "feature-${feature.name}"
            enqueue(withFeatures(version, feature), version)
            val saved = RemoteConfigRepositoryImpl(http, checkpoint).refresh()
            server.takeRequest()
            for (other in PaymentFeature.entries) assertEquals(
                other != feature,
                saved.features.isEnabled(other),
            )
            val restored = RemoteConfigRepositoryImpl(http, checkpoint)
            val requests = server.requestCount
            assertEquals(saved, restored.readCached())
            assertEquals(requests, server.requestCount)
            server.enqueue(MockResponse.Builder().code(503).build())
            expectFailure(RemoteConfigIssue.Unavailable) { restored.refresh() }
            server.takeRequest()
            assertEquals(saved, restored.readCached())
        }
        val restored = RemoteConfigRepositoryImpl(http, checkpoint)
        val invalid =
            JSONObject(withFeatures("broken")).apply {
                getJSONObject("features").getJSONObject("airtime").put("enabled", "true")
            }
        enqueue(invalid.toString(), "broken")
        expectFailure(RemoteConfigIssue.InvalidResponse) { restored.refresh() }
        server.takeRequest()
        assertFalse(restored.readCached()!!.features.airtimeConversion)
        enqueue(withFeatures("recovered"), "recovered")
        assertEquals(PaymentFeatures.AllEnabled, restored.refresh().features)
        assertEquals("\"feature-AirtimeConversion\"", server.takeRequest().headers["If-None-Match"])
    }

    /**
     * Auth-only snapshots from older app/server versions retain auth choices but never enable
     * payment features.
     */
    @Test
    fun legacySnapshotDoesNotInventFeatureAvailability() = runTest {
        enqueue()
        val saved = RemoteConfigRepositoryImpl(http, checkpoint).refresh()
        assertEquals(PaymentFeatures.Disabled, saved.features)
        assertEquals(AuthenticationMethods(true, true), saved.login)
    }

    /** Assert only safe public failure metadata; raw provider diagnostics must never escape. */
    private suspend fun expectFailure(issue: RemoteConfigIssue, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected a configuration failure")
        } catch (failure: RemoteConfigFailure) {
            assertEquals(issue, failure.issue)
            assertFalse(failure.toString().contains("sensitive"))
        }
    }
}

/**
 * Atomic checkpoint fixture with an injectable failed commit and no Android filesystem dependency.
 */
private class ConfigCheckpoint : TextCheckpoint {
    var value: String? = null
    var writes = 0
    var failWrite = false
    var failRead = false

    /** Read the last committed snapshot only. */
    override suspend fun read(): String? {
        if (failRead) {
            throw java.io.IOException("fixture")
        }
        return value
    }

    /** Reject before mutation when simulating a failed atomic replacement. */
    override suspend fun write(value: String) {
        if (failWrite) {
            throw java.io.IOException("fixture")
        }
        this.value = value
        writes++
    }
}
