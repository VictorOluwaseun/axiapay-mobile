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
import com.kesofty.axiapay.application.telecom.*
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.data.auth.HttpAuthenticationRepository
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.data.telecom.*
import com.kesofty.axiapay.data.wallet.HttpWalletRepository
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.telecom.*
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.telecom.*
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.telecom.TelecomSheet
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
class TelecomApiUiTest {
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
    @Volatile private var service = TelecomService.Airtime
    @Volatile private var status = "pending"
    @Volatile private var rejectPost = false
    @Volatile private var observed = false
    @Volatile private var productAvailable = true
    private lateinit var file: File
    private lateinit var http: ApiHttpClient
    private lateinit var sessions: TestSessions
    private lateinit var vm: TelecomViewModel

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
                                    .put("products", catalogue())
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
    private fun createViewModel(key: String): TelecomViewModel {
        val workflow =
            TelecomPurchases(
                HttpAuthenticationRepository(http, clock),
                sessions,
                HttpWalletRepository(http),
                HttpTelecomRepository(http),
                AtomicTelecomRequestStore(file, Dispatchers.IO),
                clock,
            ) {
                requestKey
            }
        return TelecomViewModel(Optional.of(workflow), PrepareTelecom(), clock).also {
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
            .put("serviceType", service.name.lowercase(Locale.ROOT))
            .put("allowanceLabel", if (service == TelecomService.Data) "1 GB" else JSONObject.NULL)
            .put(
                "validitySeconds",
                if (service == TelecomService.Data) 2592000 else JSONObject.NULL,
            )
            .put(
                "displayName",
                if (service == TelecomService.Data) "MTN 1 GB monthly" else "MTN airtime",
            )
            .put("networkCode", "mtn")
            .put("currency", "NGN")
            .put(
                "fixedPriceMinorUnits",
                if (service == TelecomService.Data) "125050" else JSONObject.NULL,
            )
            .put(
                "minimumAmountMinorUnits",
                if (service == TelecomService.Data) "125050" else "5000",
            )
            .put(
                "maximumAmountMinorUnits",
                if (service == TelecomService.Data) "125050" else "1000000",
            )
            .put("amountStepMinorUnits", "1")
            .put("feeMinorUnits", "100")
            .put("isSubmissionAvailable", productAvailable)

    /** A second API network makes plan filtering observable without local catalogue aliases. */
    private fun catalogue(): JSONArray {
        val rows = JSONArray().put(product())
        if (service == TelecomService.Data) {
            rows.put(
                product()
                    .put("id", "123e4567-e89b-42d3-a456-426614174009")
                    .put("networkCode", "airtel")
                    .put("displayName", "Airtel 1 GB monthly"),
            )
        }
        return rows
    }

    /** Embedded progress quotes intentionally omit current catalogue capability. */
    private fun quote(capability: Boolean): JSONObject {
        val result =
            JSONObject()
                .put("id", quoteId)
                .put("walletId", walletId)
                .put("productId", productId)
                .put("serviceType", service.name.lowercase(Locale.ROOT))
                .put(
                    "allowanceLabel",
                    if (service == TelecomService.Data) "1 GB" else JSONObject.NULL,
                )
                .put(
                    "validitySeconds",
                    if (service == TelecomService.Data) 2592000 else JSONObject.NULL,
                )
                .put(
                    "displayName",
                    if (service == TelecomService.Data) "MTN 1 GB monthly" else "MTN airtime",
                )
                .put("networkCode", "mtn")
                .put("recipientPhoneNumber", "+2348012345678")
                .put("currency", "NGN")
                .put(
                    "principalMinorUnits",
                    if (service == TelecomService.Data) "125050" else "10000",
                )
                .put("feeMinorUnits", "100")
                .put(
                    "totalDebitMinorUnits",
                    if (service == TelecomService.Data) "125150" else "10100",
                )
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
                service.name,
                PaymentInput(
                    if (service == TelecomService.Data) PaymentType.Data else PaymentType.Airtime,
                    date = LocalDate.now(clock),
                ),
            )
        val state =
            AxiaUiState(
                DemoSessionSeed.create(LocalDate.now(clock)).copy(offline = offline),
                NavigationState(sheet = Sheet.Payment(form)),
            )
        compose.setContent {
            val telecom by vm.uiState.collectAsStateWithLifecycle()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AxiapayTheme(offline) {
                    if (sheetOnly) {
                        Surface(Modifier.fillMaxSize()) {
                            TelecomSheet(telecom, offline, false, vm::accept, {})
                        }
                    } else {
                        AxiaApp(
                            state,
                            {},
                            homeWallet =
                                telecom.context?.wallet?.let { HomeWalletState.Loaded(it) }
                                    ?: HomeWalletState.Loading,
                            telecom = telecom,
                            onTelecomIntent = vm::accept,
                        )
                    }
                }
            }
        }
        compose.runOnIdle { vm.accept(TelecomIntent.Bind(accountId, "visit", service)) }
        waitFor(TelecomScreen.Form)
    }

    /** Wait for bounded IO without using animation/timer idleness as a payment signal. */
    private fun waitFor(screen: TelecomScreen) {
        compose.waitUntil(15_000) {
            vm.uiState.value.screen == screen && !vm.uiState.value.isProcessing
        }
    }

    /** Enter the server product, canonical recipient and exact amount through real fields. */
    private fun review() {
        if (service == TelecomService.Data) {
            compose.onNodeWithTag("telecom-network-mtn").performScrollTo().performClick()
        }
        compose.onNodeWithTag("telecom-product-$productId").performScrollTo().performClick()
        compose.onNodeWithTag("telecom-recipient").performScrollTo().performTextInput("08012345678")
        if (service == TelecomService.Airtime) {
            compose.onNodeWithTag("telecom-amount").performScrollTo().performTextInput("100")
        }
        compose.onNodeWithTag("telecom-review").performScrollTo().performClick()
        waitFor(TelecomScreen.Review)
    }

    /** Full UI quote/confirm/status flow uses the owned wallet, with one explicit consent POST. */
    @Test
    fun livePurchaseUsesWalletAndOnlyServerSuccessCreatesAReceipt() {
        show()
        review()
        assertEquals(0, posts.get())
        compose
            .onNodeWithTag("telecom-total")
            .performScrollTo()
            .assertTextContains("101.00", substring = true)
        compose.onNodeWithTag("telecom-confirm").performScrollTo().performClick()
        waitFor(TelecomScreen.Progress)
        compose
            .onNodeWithTag("telecom-status")
            .performScrollTo()
            .assertTextEquals("Payment pending")
        assertEquals(1, posts.get())
        val quoteRequest = requests.first { it.target == "/v1/payments/quotes" }
        assertEquals(walletId, JSONObject(quoteRequest.body!!.utf8()).getString("walletId"))
        val command = JSONObject(requests.first { it.target == "/v1/payments" }.body!!.utf8())
        assertEquals(2, command.length())
        assertEquals(requestKey, command.getString("requestKey"))
        compose.onNodeWithTag("telecom-reconcile").performScrollTo().performClick()
        waitFor(TelecomScreen.Progress)
        compose
            .onNodeWithTag("telecom-status")
            .performScrollTo()
            .assertTextEquals("Airtime purchased")
        assertEquals(1, posts.get())
        compose.onNodeWithTag("telecom-done").performScrollTo().performClick()
        compose.waitUntil(5000) { !vm.uiState.value.isProcessing }
        val records = runBlocking {
            AtomicTelecomRequestStore(file, Dispatchers.IO)
                .read(TelecomOwner(accountId, http.apiIdentity))
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
        compose.onNodeWithTag("telecom-confirm").performScrollTo().performClick()
        waitFor(TelecomScreen.Progress)
        compose
            .onNodeWithTag("telecom-status")
            .performScrollTo()
            .assertTextEquals("Payment status unconfirmed")
        assertEquals(1, posts.get())
        compose.runOnIdle {
            vm.accept(TelecomIntent.Bind(null, null))
            owners.clear()
            vm = createViewModel("cold")
            vm.accept(TelecomIntent.Bind(accountId, "cold", service))
        }
        waitFor(TelecomScreen.History)
        assertEquals(1, posts.get())
        status = "succeeded"
        compose.runOnIdle { vm.accept(TelecomIntent.CheckSaved(requestKey)) }
        waitFor(TelecomScreen.Progress)
        assertEquals(TelecomPaymentStatus.Succeeded, vm.uiState.value.progress?.payment?.status)
        assertEquals(1, posts.get())
        assertEquals(1, quotes.get())
    }

    /** Unavailable deployment and offline state never fall back to simulated funds or SMS. */
    @Test
    fun unavailableCatalogueAndOfflineStateDisablePurchases() {
        productAvailable = false
        show(offline = true)
        compose.onNodeWithTag("telecom-product-$productId").performScrollTo().performClick()
        compose.onNodeWithTag("telecom-recipient").performScrollTo().performTextInput("08012345678")
        compose.onNodeWithTag("telecom-amount").performScrollTo().performTextInput("100")
        compose.onNodeWithTag("telecom-review").performScrollTo().assertIsNotEnabled()
        assertEquals(0, posts.get())
        assertEquals(0, quotes.get())
    }

    /** Exact review terms and consent remain reachable in a compact double-font layout. */
    @Test
    fun reviewRemainsReadableWithLargeFonts() {
        show(fontScale = 2f, sheetOnly = true)
        review()
        compose.onNodeWithTag("telecom-total").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag("telecom-confirm")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        val screenshot =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "telecom-review-large-font.png",
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

    /** Actual data UI uses a fixed API plan, exact quote, owned wallet and one explicit POST. */
    @Test
    fun dataPlanPurchaseOmitsPriceOverrideAndDisplaysServerReceipt() {
        service = TelecomService.Data
        status = "succeeded"
        show()
        review()
        compose.onNodeWithTag("telecom-amount").assertDoesNotExist()
        compose.onNodeWithText("1 GB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("30 days").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag("telecom-total")
            .performScrollTo()
            .assertTextContains("1,251.50", substring = true)
        compose.onNodeWithTag("telecom-confirm").performScrollTo().performClick()
        waitFor(TelecomScreen.Progress)
        compose.onNodeWithTag("telecom-status").performScrollTo().assertTextEquals("Data purchased")
        val quoteCommand =
            JSONObject(requests.first { it.target == "/v1/payments/quotes" }.body!!.utf8())
        assertEquals(walletId, quoteCommand.getString("walletId"))
        assertEquals(productId, quoteCommand.getString("productId"))
        assertEquals(3, quoteCommand.length())
        assertFalse(quoteCommand.has("amountMinorUnits"))
        assertTrue(
            requests.any {
                it.target == "/v1/service-catalog/products?serviceType=data&currency=NGN&limit=25"
            },
        )
        assertEquals(1, posts.get())
        assertEquals(
            TelecomService.Data,
            runBlocking {
                AtomicTelecomRequestStore(file, Dispatchers.IO)
                    .read(fixtureOwner())
                    .single()
                    .service
            },
        )
    }

    /** Phone-service recovery records are scoped to this fixture account/API. */
    private fun fixtureOwner() = TelecomOwner(accountId, http.apiIdentity)

    /** A timed-out data response survives a new owner/store and status checks do not buy again. */
    @Test
    fun dataRequestSurvivesColdRestoreWithoutAnotherPurchase() {
        service = TelecomService.Data
        rejectPost = true
        show()
        review()
        compose.onNodeWithTag("telecom-confirm").performScrollTo().performClick()
        waitFor(TelecomScreen.Progress)
        compose
            .onNodeWithTag("telecom-status")
            .performScrollTo()
            .assertTextEquals("Payment status unconfirmed")
        compose.runOnIdle {
            vm.accept(TelecomIntent.Bind(null, null))
            owners.clear()
            vm = createViewModel("data-cold")
            vm.accept(TelecomIntent.Bind(accountId, "cold", TelecomService.Data))
        }
        waitFor(TelecomScreen.History)
        assertEquals(1, posts.get())
        status = "succeeded"
        compose.runOnIdle { vm.accept(TelecomIntent.CheckSaved(requestKey)) }
        waitFor(TelecomScreen.Progress)
        assertEquals(TelecomService.Data, vm.uiState.value.progress?.payment?.quote?.service)
        assertEquals(TelecomPaymentStatus.Succeeded, vm.uiState.value.progress?.payment?.status)
        assertEquals(1, posts.get())
        assertEquals(1, quotes.get())
    }

    /** Switching networks clears the current plan and cannot review a hidden provider's product. */
    @Test
    fun dataNetworkChangeClearsSelectionAndHidesOtherPlans() {
        service = TelecomService.Data
        show()
        compose.onNodeWithTag("telecom-product-$productId").assertDoesNotExist()
        compose.onNodeWithTag("telecom-network-mtn").performScrollTo().performClick()
        compose.onNodeWithTag("telecom-product-$productId").performScrollTo().performClick()
        compose.onNodeWithTag("telecom-recipient").performScrollTo().performTextInput("08012345678")
        compose.onNodeWithTag("telecom-review").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("telecom-network-airtel").performScrollTo().performClick()
        compose.onNodeWithTag("telecom-product-$productId").assertDoesNotExist()
        compose.onNodeWithTag("telecom-review").performScrollTo().assertIsNotEnabled()
        assertEquals(0, quotes.get())
        assertEquals(0, posts.get())
    }

    /** A valid data plan cannot be submitted while the real purchase flow is offline. */
    @Test
    fun offlineDataRequiresInternetAndNeverFallsBackToSmsOrDemo() {
        service = TelecomService.Data
        show(offline = true)
        compose.onNodeWithTag("telecom-network-mtn").performScrollTo().performClick()
        compose.onNodeWithTag("telecom-product-$productId").performScrollTo().performClick()
        compose.onNodeWithTag("telecom-recipient").performScrollTo().performTextInput("08012345678")
        compose.onNodeWithTag("telecom-review").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Confirm demo payment").assertDoesNotExist()
        assertEquals(0, quotes.get())
        assertEquals(0, posts.get())
    }

    /** Data allowance, validity, fee and consent remain readable at double font size. */
    @Test
    fun dataReviewRemainsReadableWithLargeFonts() {
        service = TelecomService.Data
        show(fontScale = 2f, sheetOnly = true)
        review()
        compose.onNodeWithText("1 GB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("30 days").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag("telecom-confirm")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        val screenshot =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "data-review-large-font.png",
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
