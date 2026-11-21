package com.kesofty.axiapay

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.auth.HttpAuthenticationRepository
import com.kesofty.axiapay.data.database.AxiaDatabase
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import com.kesofty.axiapay.wallet.application.*
import com.kesofty.axiapay.wallet.data.FundingRepositoryImpl
import com.kesofty.axiapay.wallet.data.WalletRepositoryImpl
import com.kesofty.axiapay.wallet.data.local.RoomWalletLocalDataSource
import com.kesofty.axiapay.wallet.data.remote.FundingRemoteDataSource
import com.kesofty.axiapay.wallet.data.remote.HttpWalletRemoteDataSource
import com.kesofty.axiapay.wallet.domain.FundingIssue
import com.kesofty.axiapay.wallet.presentation.*
import com.kesofty.axiapay.wallet.ui.FundingSheet
import java.io.File
import java.time.*
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/**
 * Exercise real Compose, ViewModel, application and HTTP layers against fixture-only deposit
 * progress.
 */
class FundingApiUiTest {
    @get:Rule val compose = createComposeRule()
    private val owners = ViewModelStore()
    private val server = MockWebServer()
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    private val now = Instant.parse("2026-10-24T10:00:00Z")
    private val accountId = "123e4567-e89b-42d3-a456-426614174000"
    private val walletId = "123e4567-e89b-42d3-a456-426614174001"
    private val fundingId = "123e4567-e89b-42d3-a456-426614174004"
    private val posts = AtomicInteger()
    private val reconciles = AtomicInteger()
    @Volatile private var status = "awaiting_transfer"
    @Volatile private var reference: String? = null
    @Volatile private var created = false
    @Volatile private var selectedOption = "vtpass-bank-transfer"
    private lateinit var database: AxiaDatabase
    private lateinit var vm: FundingViewModel
    private val saved = SavedStateHandle()
    private var fontScale by mutableFloatStateOf(1f)
    private var offline by mutableStateOf(false)

    /** Bind real adapters to a bounded localhost fixture; no live account is read or modified. */
    @Before
    fun start() {
        Assume.assumeTrue(
            "Loopback fixture requires dev network policy",
            BuildConfig.FLAVOR == "dev",
        )
        server.dispatcher =
            object : Dispatcher() {
                /** Expose only the documented funding/auth/wallet contract. */
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.target
                    if (path == "/api/v1/auth/me") {
                        return response(
                            200,
                            """{"id":"$accountId","contact":{"kind":"email","value":"fixture@example.test"},"verifiedAt":"2026-10-24T09:00:00Z"}""",
                        )
                    }
                    if (path == "/api/v1/wallets/me?currency=NGN") {
                        return response(
                            200,
                            """{"id":"$walletId","currency":"NGN","walletStatus":"active","availableMinorUnits":"0","reservedMinorUnits":"0","totalMinorUnits":"0"}""",
                        )
                    }
                    if (path.startsWith("/api/v1/wallets/$walletId/funding-context")) {
                        return response(
                            200,
                            """{"options":[{"id":"vtpass-bank-transfer","displayName":"Bank transfer","currency":"NGN","flow":"bank_transfer","minimumAmountMinorUnits":"100","maximumAmountMinorUnits":"100000000"},{"id":"hosted-checkout","displayName":"Hosted checkout","currency":"NGN","flow":"hosted_checkout","minimumAmountMinorUnits":"100","maximumAmountMinorUnits":"100000000"}],"requests":[${if (created) fundingJson() else ""}],"nextOffset":null}""",
                        )
                    }
                    if (path == "/api/v1/wallets/$walletId/funding-requests") {
                        posts.incrementAndGet()
                        val input = JSONObject(request.body!!.utf8())
                        val option = input.getString("fundingOptionId")
                        if (
                            option == "hosted-checkout" &&
                                input.optString("payerEmail") != "corrected@example.test"
                        ) {
                            return response(400, """{"code":"INVALID_INPUT","statusCode":400}""")
                        }
                        selectedOption = option
                        if (option == "hosted-checkout") status = "pending"
                        created = true
                        return response(202, fundingJson())
                    }
                    if (path == "/api/v1/wallet-funding/$fundingId/reference") {
                        reference = JSONObject(request.body!!.utf8()).getString("paymentReference")
                        status = "awaiting_confirmation"
                        return response(200, fundingJson())
                    }
                    if (path == "/api/v1/wallet-funding/$fundingId/reconcile") {
                        reconciles.incrementAndGet()
                        return response(200, fundingJson())
                    }
                    if (path == "/api/v1/wallet-funding/$fundingId") {
                        return response(200, fundingJson())
                    }
                    return response(404, "{}")
                }
            }
        server.start()
        val http = ApiHttpClient(server.url("/api/v1/").toString(), true, client, Dispatchers.IO)
        val authentication = HttpAuthenticationRepository(http, Clock.fixed(now, ZoneOffset.UTC))
        val sessions =
            FundingTestSessions(
                StoredAuthenticationSession(
                    "a".repeat(43),
                    now.plusSeconds(3600),
                    http.apiIdentity,
                    accountId,
                ),
            )
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        database =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    AxiaDatabase::class.java,
                )
                .build()
        val walletRepository =
            WalletRepositoryImpl(
                HttpWalletRemoteDataSource(http),
                RoomWalletLocalDataSource(database, clock),
            )
        val fundingRepository = FundingRepositoryImpl(FundingRemoteDataSource(http))
        val access = FundingSessionAccess(authentication, sessions, clock)
        val useCases =
            FundingUseCases(
                loadFundingContext =
                    LoadFundingContext(access, walletRepository, fundingRepository),
                prepareFundingRequest = PrepareFundingRequest(),
                createFundingRequest = CreateFundingRequest(access, fundingRepository),
                getFundingRequest = GetFundingRequest(access, fundingRepository),
                submitFundingReference = SubmitFundingReference(access, fundingRepository),
                reconcileFundingRequest = ReconcileFundingRequest(access, fundingRepository),
            )
        vm = FundingViewModel(Optional.of(useCases), saved)
        owners.put("funding", vm)
    }

    /** Close every UI owner and fixture socket. */
    @After
    fun close() {
        owners.clear()
        if (::database.isInitialized) database.close()
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    /**
     * Reproduce the real server snapshot while allowing the test to advance only verified status.
     */
    private fun fundingJson(): String =
        JSONObject()
            .put("fundingId", fundingId)
            .put("walletId", walletId)
            .put("fundingOptionId", selectedOption)
            .put("amountMinorUnits", "2500000")
            .put("currency", "NGN")
            .put("fundingStatus", status)
            .put(
                "checkoutUrl",
                if (selectedOption == "hosted-checkout") "https://checkout.example.test/fixture"
                else JSONObject.NULL,
            )
            .put(
                "operationId",
                if (status == "credited") "123e4567-e89b-42d3-a456-426614174005"
                else JSONObject.NULL,
            )
            .put(
                "transferInstructions",
                if (selectedOption == "hosted-checkout") JSONObject.NULL
                else
                    JSONObject()
                        .put("bankName", "Fixture Bank")
                        .put("accountName", "AxiaPay test collection")
                        .put("accountNumber", "0000000001"),
            )
            .put("customerReference", reference ?: JSONObject.NULL)
            .put("reviewNote", JSONObject.NULL)
            .toString()

    /** Keep response metadata identical to the API. */
    private fun response(code: Int, body: String) =
        MockResponse.Builder()
            .code(code)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()

    /**
     * Render the production screen with a lifecycle-aware owner and in-composition font scaling.
     */
    private fun render() {
        compose.setContent {
            val state by vm.uiState.collectAsStateWithLifecycle()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AxiapayTheme(offline = offline) {
                    Surface(Modifier.fillMaxSize()) { FundingSheet(state, offline, vm::accept) }
                }
            }
        }
        compose.runOnIdle { vm.accept(FundingIntent.Bind(accountId, "funding")) }
        compose.waitUntil(10000) { vm.uiState.value.context != null && !vm.uiState.value.busy }
    }

    /** Capture safe fixture screens into app-owned files for visual review. */
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use { output ->
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    /**
     * Customer reference and app/navigation return remain pending until an explicit
     * server-confirmed credit.
     */
    @Test
    fun transferReferenceRestorationAndCreditProgress() {
        render()
        compose.onNodeWithText("Amount (NGN)").performTextInput("25000")
        compose.onNodeWithText("Get funding instructions").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.uiState.value.request != null && !vm.uiState.value.busy }
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("0000000001").assertExists()
        compose.onNodeWithText("Copy account number").performScrollTo().performClick()
        compose.onNodeWithText("Add money", useUnmergedTree = true).performScrollTo()
        screenshot("funding-transfer.png")
        compose
            .onNodeWithText("Your transfer reference")
            .performScrollTo()
            .performTextInput("FIXTURE-TRANSFER-101")
        compose.onNodeWithText("Submit reference for review").performScrollTo().performClick()
        compose.waitUntil(10000) {
            vm.uiState.value.request?.customerReference != null && !vm.uiState.value.busy
        }
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Awaiting deposit review").assertExists()
        screenshot("funding-pending.png")
        compose.runOnIdle {
            vm.accept(FundingIntent.Bind(accountId, null))
            vm.accept(FundingIntent.Bind(accountId, "returned"))
        }
        compose.waitUntil(10000) { !vm.uiState.value.busy }
        assertEquals(1, posts.get())
        assertEquals(0, reconciles.get())
        status = "approved" // Unsupported server states must not appear as success.
        compose.onNodeWithText("Refresh progress").performScrollTo().performClick()
        compose.waitUntil(10000) { !vm.uiState.value.busy && vm.uiState.value.issue != null }
        compose.onNodeWithText("Money added to your wallet").assertDoesNotExist()
        status = "awaiting_credit"
        compose.onNodeWithText("Refresh progress").performScrollTo().performClick()
        compose.waitUntil(10000) {
            vm.uiState.value.request?.status ==
                com.kesofty.axiapay.wallet.domain.FundingStatus.AwaitingCredit
        }
        compose.onNodeWithText("Deposit approved · wallet credit pending").assertExists()
        status = "credited"
        compose.onNodeWithText("Refresh progress").performScrollTo().performClick()
        compose.waitUntil(10000) {
            vm.uiState.value.request?.status ==
                com.kesofty.axiapay.wallet.domain.FundingStatus.Credited
        }
        compose.onNodeWithText("Money added to your wallet").assertExists()
        assertEquals(1, posts.get())
    }

    /**
     * A rejected checkout email becomes editable and the next explicit submit uses the corrected
     * value.
     */
    @Test
    fun customerCanCorrectAnEmailRejectedByTheApi() {
        render()
        compose.onNodeWithText("Hosted checkout").performScrollTo().performClick()
        compose.onNodeWithText("Amount (NGN)").performTextInput("25000")
        val email = compose.onNodeWithText("Receipt email")
        email.performScrollTo().performTextInput("invalid-email")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Get funding instructions").performScrollTo().performClick()
        compose.waitUntil(10000) {
            vm.uiState.value.issue == FundingIssue.InvalidInput && !vm.uiState.value.busy
        }
        email.performScrollTo().assertIsEnabled().performTextReplacement("corrected@example.test")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Get funding instructions").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.uiState.value.request != null && !vm.uiState.value.busy }
        assertEquals(2, posts.get())
        assertEquals("hosted-checkout", vm.uiState.value.request?.optionId)
        compose.onNodeWithText("Open secure checkout").performScrollTo().assertIsEnabled()
    }

    /**
     * Large text and offline palettes keep instructions readable and disable network submissions.
     */
    @Test
    fun largeTextOfflineInstructionsRemainAccessible() {
        created = true
        saved["funding.account"] = accountId
        saved["funding.request"] = fundingId
        fontScale = 1.6f
        render()
        compose.waitUntil(10000) { vm.uiState.value.request != null }
        compose.runOnIdle { offline = true }
        compose.onNodeWithText("Copy account number").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Open OPay").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Submit reference for review").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Add money", useUnmergedTree = true).performScrollTo()
        screenshot("funding-large-offline.png")
        assertEquals(0, posts.get())
        assertEquals(0, reconciles.get())
    }
}

/** In-memory isolated credential storage never touches the installed user's session. */
private class FundingTestSessions(private var session: StoredAuthenticationSession?) :
    AuthenticationSessionStore {
    /** Return the isolated fixture session. */
    override suspend fun read() = session

    /** Save only test credentials. */
    override suspend fun write(session: StoredAuthenticationSession) {
        this.session = session
    }

    /** Model atomic replacement within the test login. */
    override suspend fun replace(
        expected: StoredAuthenticationSession,
        replacement: StoredAuthenticationSession,
    ): Boolean {
        if (session != expected) {
            return false
        }
        session = replacement
        return true
    }

    /** Clear only the captured fixture session. */
    override suspend fun clear(expected: StoredAuthenticationSession) {
        if (session == expected) {
            session = null
        }
    }
}
