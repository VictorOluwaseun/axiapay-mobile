package com.kesofty.axiapay

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.application.airtime.*
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.data.airtime.*
import com.kesofty.axiapay.data.auth.HttpAuthenticationRepository
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.data.wallet.HttpWalletRepository
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.domain.airtime.*
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.airtime.*
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.airtime.AirtimeSheet
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import java.io.File
import java.time.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.json.*
import org.junit.*
import org.junit.Assert.*

/** End-to-end UI/application/HTTP/journal checks use a disposable API and no real funds. */
class AirtimeApiUiTest {
    @get:Rule val compose = createComposeRule()
    private val owners = ViewModelStore()
    private val server = MockWebServer()
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val accountId = "123e4567-e89b-42d3-a456-426614174000"
    private val walletId = "123e4567-e89b-42d3-a456-426614174001"
    private val productId = "123e4567-e89b-42d3-a456-426614174002"
    private val quoteId = "123e4567-e89b-42d3-a456-426614174003"
    private val paymentId = "123e4567-e89b-42d3-a456-426614174004"
    private val requestKey = "123e4567-e89b-42d3-a456-426614174005"
    private val posts = AtomicInteger()
    private val quotes = AtomicInteger()
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    @Volatile private var status = "pending"
    @Volatile private var rejectPost = false
    @Volatile private var observed = false
    @Volatile private var productAvailable = true
    private lateinit var file: File
    private lateinit var http: ApiHttpClient
    private lateinit var sessions: TestSessions
    private lateinit var vm: AirtimeViewModel

    /** Route only documented first-party requests; unexpected network paths fail the test. */
    @Before
    fun start() {
        Assume.assumeTrue(
            "Loopback fixture requires the dev network policy",
            BuildConfig.FLAVOR == "dev",
        )
        file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                "test-airtime-${UUID.randomUUID()}.json",
            )
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.headers["Authorization"] != "Bearer ${"a".repeat(43)}") {
                        return response(401, "{}")
                    }
                    return when {
                        request.target == "/v1/auth/me" ->
                            response(
                                200,
                                JSONObject()
                                    .put("id", accountId)
                                    .put(
                                        "contact",
                                        JSONObject()
                                            .put("kind", "email")
                                            .put("value", "airtime@example.test"),
                                    )
                                    .put("verifiedAt", now.toString())
                                    .toString(),
                            )
                        request.target.startsWith("/v1/wallets/me?") ->
                            response(
                                200,
                                JSONObject()
                                    .put("id", walletId)
                                    .put("currency", "NGN")
                                    .put("walletStatus", "active")
                                    .put("availableMinorUnits", "500000")
                                    .put("reservedMinorUnits", "0")
                                    .put("totalMinorUnits", "500000")
                                    .toString(),
                            )
                        request.target.startsWith("/v1/service-catalog/products?") ->
                            response(
                                200,
                                JSONObject()
                                    .put("products", JSONArray().put(product()))
                                    .put("nextCursor", JSONObject.NULL)
                                    .toString(),
                            )
                        request.target == "/v1/payments/quotes" -> {
                            quotes.incrementAndGet()
                            response(201, quote(true).toString())
                        }
                        request.target == "/v1/payments/quotes/$quoteId" ->
                            response(200, quote(true).toString())
                        request.target == "/v1/payments" -> {
                            posts.incrementAndGet()
                            observed = true
                            if (rejectPost) response(503, "{\"code\":\"PAYMENT_UNAVAILABLE\"}")
                            else response(200, payment().toString())
                        }
                        request.target.startsWith("/v1/payments/by-request-key?") ->
                            if (observed) response(200, payment().toString())
                            else response(404, "{}")
                        request.target == "/v1/payments/$paymentId/reconcile" -> {
                            status = "succeeded"
                            response(200, payment().toString())
                        }
                        else -> response(500, "{}")
                    }
                }
            }
        server.start()
        http = ApiHttpClient(server.url("/v1/").toString(), true, client, Dispatchers.IO)
        sessions =
            TestSessions(
                StoredAuthenticationSession(
                    "a".repeat(43),
                    now.plusSeconds(3600),
                    http.apiIdentity,
                ),
            )
        compose.runOnIdle { vm = createViewModel("first") }
    }

    /** Close test-owned files, UI jobs and sockets without touching the real credential store. */
    @After
    fun close() {
        compose.runOnIdle { owners.clear() }
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        if (::file.isInitialized) {
            file.delete()
            File(file.path + ".bak").delete()
        }
    }

    /** Construct real adapters/workflow with isolated session persistence for a test UI owner. */
    private fun createViewModel(key: String): AirtimeViewModel {
        val workflow =
            AirtimePurchases(
                HttpAuthenticationRepository(http, clock),
                sessions,
                HttpWalletRepository(http),
                HttpAirtimeRepository(http),
                AtomicAirtimeRequestStore(file, Dispatchers.IO),
                clock,
            ) {
                requestKey
            }
        return AirtimeViewModel(Optional.of(workflow), PrepareAirtime(), clock).also {
            owners.put(key, it)
        }
    }

    /** JSON response shapes match the API DTOs rather than provider payloads. */
    private fun response(code: Int, content: String) =
        MockResponse.Builder()
            .code(code)
            .setHeader("Content-Type", "application/json")
            .body(content)
            .build()

    /** Public catalogue availability is honored in the UI. */
    private fun product() =
        JSONObject()
            .put("id", productId)
            .put("serviceType", "airtime")
            .put("displayName", "MTN airtime")
            .put("networkCode", "mtn")
            .put("currency", "NGN")
            .put("fixedPriceMinorUnits", JSONObject.NULL)
            .put("minimumAmountMinorUnits", "5000")
            .put("maximumAmountMinorUnits", "1000000")
            .put("amountStepMinorUnits", "1")
            .put("feeMinorUnits", "100")
            .put("isSubmissionAvailable", productAvailable)

    /** Embedded progress quotes intentionally omit current catalogue capability. */
    private fun quote(capability: Boolean): JSONObject {
        val result =
            JSONObject()
                .put("id", quoteId)
                .put("walletId", walletId)
                .put("productId", productId)
                .put("serviceType", "airtime")
                .put("displayName", "MTN airtime")
                .put("networkCode", "mtn")
                .put("recipientPhoneNumber", "+2348012345678")
                .put("currency", "NGN")
                .put("principalMinorUnits", "10000")
                .put("feeMinorUnits", "100")
                .put("totalDebitMinorUnits", "10100")
                .put("createdAt", now.toString())
                .put("expiresAt", now.plusSeconds(120).toString())
        if (capability) {
            result.put("isSubmissionAvailable", productAvailable)
        }
        return result
    }

    /** Only this synthetic committed response can establish final UI success. */
    private fun payment() =
        JSONObject()
            .put("id", paymentId)
            .put("requestKey", requestKey)
            .put("paymentStatus", status)
            .put("quote", quote(false))
            .put("createdAt", now.toString())
            .put("submissionStartedAt", now.toString())

    /** Render AppSheet integration; direct sheet rendering makes large-font pixels capturable. */
    private fun show(offline: Boolean = false, fontScale: Float = 1f, sheetOnly: Boolean = false) {
        val form =
            PaymentFormState(
                "visit",
                "Airtime",
                PaymentInput(PaymentType.Airtime, date = LocalDate.now(clock)),
            )
        val state =
            AxiaUiState(
                DemoSessionSeed.create(LocalDate.now(clock)).copy(offline = offline),
                NavigationState(sheet = Sheet.Payment(form)),
            )
        compose.setContent {
            val airtime by vm.uiState.collectAsStateWithLifecycle()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AxiapayTheme(offline) {
                    if (sheetOnly) {
                        Surface(Modifier.fillMaxSize()) {
                            AirtimeSheet(airtime, offline, false, vm::accept, {})
                        }
                    } else {
                        AxiaApp(
                            state,
                            {},
                            homeWallet =
                                airtime.context?.wallet?.let { HomeWalletState.Loaded(it) }
                                    ?: HomeWalletState.Loading,
                            airtime = airtime,
                            onAirtimeIntent = vm::accept,
                        )
                    }
                }
            }
        }
        compose.runOnIdle { vm.accept(AirtimeIntent.Bind(accountId, "visit")) }
        waitFor(AirtimeScreen.Form)
    }

    /** Wait for bounded IO without using animation/timer idleness as a payment signal. */
    private fun waitFor(screen: AirtimeScreen) {
        compose.waitUntil(15_000) {
            vm.uiState.value.screen == screen && !vm.uiState.value.isProcessing
        }
    }

    /** Enter the server product, canonical recipient and exact amount through real fields. */
    private fun review() {
        compose.onNodeWithTag("airtime-product-$productId").performScrollTo().performClick()
        compose.onNodeWithTag("airtime-recipient").performScrollTo().performTextInput("08012345678")
        compose.onNodeWithTag("airtime-amount").performScrollTo().performTextInput("100")
        compose.onNodeWithTag("airtime-review").performScrollTo().performClick()
        waitFor(AirtimeScreen.Review)
    }

    /** Full UI quote/confirm/status flow uses the owned wallet, with one explicit consent POST. */
    @Test
    fun livePurchaseUsesWalletAndOnlyServerSuccessCreatesAReceipt() {
        show()
        review()
        assertEquals(0, posts.get())
        compose
            .onNodeWithTag("airtime-total")
            .performScrollTo()
            .assertTextContains("101.00", substring = true)
        compose.onNodeWithTag("airtime-confirm").performScrollTo().performClick()
        waitFor(AirtimeScreen.Progress)
        compose
            .onNodeWithTag("airtime-status")
            .performScrollTo()
            .assertTextEquals("Payment pending")
        assertEquals(1, posts.get())
        val quoteRequest = requests.first { it.target == "/v1/payments/quotes" }
        assertEquals(walletId, JSONObject(quoteRequest.body!!.utf8()).getString("walletId"))
        val command = JSONObject(requests.first { it.target == "/v1/payments" }.body!!.utf8())
        assertEquals(2, command.length())
        assertEquals(requestKey, command.getString("requestKey"))
        compose.onNodeWithTag("airtime-reconcile").performScrollTo().performClick()
        waitFor(AirtimeScreen.Progress)
        compose
            .onNodeWithTag("airtime-status")
            .performScrollTo()
            .assertTextEquals("Airtime purchased")
        assertEquals(1, posts.get())
        compose.onNodeWithTag("airtime-done").performScrollTo().performClick()
        compose.waitUntil(5000) { !vm.uiState.value.isProcessing }
        val records = runBlocking {
            AtomicAirtimeRequestStore(file, Dispatchers.IO)
                .read(AirtimeOwner(accountId, http.apiIdentity))
        }
        assertTrue(records.single().acknowledged)
        val text = file.readText()
        assertFalse(text.contains("08012345678"))
        assertFalse(text.contains("accessToken"))
        assertFalse(text.contains("10000"))
    }

    /** Lost response plus a cold owner/store reopens saved references and performs reads only. */
    @Test
    fun uncertainPurchaseSurvivesColdOwnerAndDoesNotReplay() {
        rejectPost = true
        show()
        review()
        compose.onNodeWithTag("airtime-confirm").performScrollTo().performClick()
        waitFor(AirtimeScreen.Progress)
        compose
            .onNodeWithTag("airtime-status")
            .performScrollTo()
            .assertTextEquals("Payment status unconfirmed")
        assertEquals(1, posts.get())
        compose.runOnIdle {
            vm.accept(AirtimeIntent.Bind(null, null))
            owners.clear()
            vm = createViewModel("cold")
            vm.accept(AirtimeIntent.Bind(accountId, "cold"))
        }
        waitFor(AirtimeScreen.History)
        assertEquals(1, posts.get())
        status = "succeeded"
        compose.runOnIdle { vm.accept(AirtimeIntent.CheckSaved(requestKey)) }
        waitFor(AirtimeScreen.Progress)
        assertEquals(AirtimePaymentStatus.Succeeded, vm.uiState.value.progress?.payment?.status)
        assertEquals(1, posts.get())
        assertEquals(1, quotes.get())
    }

    /** Unavailable deployment and offline state never fall back to simulated funds or SMS. */
    @Test
    fun unavailableCatalogueAndOfflineStateDisablePurchases() {
        productAvailable = false
        show(offline = true)
        compose.onNodeWithTag("airtime-product-$productId").performScrollTo().performClick()
        compose.onNodeWithTag("airtime-recipient").performScrollTo().performTextInput("08012345678")
        compose.onNodeWithTag("airtime-amount").performScrollTo().performTextInput("100")
        compose.onNodeWithTag("airtime-review").performScrollTo().assertIsNotEnabled()
        assertEquals(0, posts.get())
        assertEquals(0, quotes.get())
    }

    /** Exact review terms and consent remain reachable in a compact double-font layout. */
    @Test
    fun reviewRemainsReadableWithLargeFonts() {
        show(fontScale = 2f, sheetOnly = true)
        review()
        compose.onNodeWithTag("airtime-total").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag("airtime-confirm")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        val screenshot =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "airtime-review-large-font.png",
            )
        screenshot.outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertEquals(0, posts.get())
    }

    /** Test-only credential port remains distinct from the app's encrypted real session store. */
    private class TestSessions(var value: StoredAuthenticationSession?) :
        AuthenticationSessionStore {
        override suspend fun read() = value

        override suspend fun write(session: StoredAuthenticationSession) {
            value = session
        }

        override suspend fun clear(expected: StoredAuthenticationSession) {
            if (value == expected) {
                value = null
            }
        }
    }
}
