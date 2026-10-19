package com.kesofty.axiapay

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.application.auth.ServerAccountAccess
import com.kesofty.axiapay.application.config.LoadRemoteConfiguration
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.data.auth.HttpAuthenticationRepository
import com.kesofty.axiapay.data.dashboard.DashboardRepositoryImpl
import com.kesofty.axiapay.data.database.*
import com.kesofty.axiapay.data.http.ApiHttpClient
import com.kesofty.axiapay.data.wallet.HttpWalletRepository
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.config.*
import com.kesofty.axiapay.domain.wallet.*
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.auth.*
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import java.io.File
import java.math.BigInteger
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*

/**
 * Real Room, application orchestration and Compose dashboard, isolated from the customer's session.
 */
class DashboardRoomUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory =
        File(context.noBackupFilesDir, "dashboard-ui-tests/${UUID.randomUUID()}")
    private lateinit var database: AxiaDatabase
    private val owner = ViewModelStore()
    private val client = OkHttpClient()
    private val gate = CompletableDeferred<Unit>()
    private val clock = Clock.systemUTC()

    /** Cancel only this test's model and close its private database and HTTP pool. */
    @After
    fun close() {
        gate.complete(Unit)
        compose.runOnUiThread { owner.clear() }
        if (::database.isInitialized) database.close()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        directory.deleteRecursively()
    }

    /**
     * A reopened cache renders before network completion, survives failure, and accepts a later
     * server refresh.
     */
    @Test
    fun reopenedDatabasePowersDashboardBeforeRefreshCompletes() {
        val account =
            AccountIdentity(
                "123e4567-e89b-42d3-a456-426614174000",
                AccountContact(AuthContactType.Email, "fixture@example.test"),
                clock.instant(),
            )
        val wallet =
            WalletSummary(
                "123e4567-e89b-42d3-a456-426614174001",
                "NGN",
                WalletStatus.Active,
                BigInteger("12500"),
                BigInteger("2500"),
                BigInteger("15000"),
            )
        val http = ApiHttpClient("https://api.example.test/api/v1/", false, client, Dispatchers.IO)
        directory.mkdirs()
        val file = File(directory, "dashboard.db").absolutePath
        database = Room.databaseBuilder(context, AxiaDatabase::class.java, file).build()
        runBlocking {
            val snapshots = DashboardRepositoryImpl(database, clock)
            snapshots.saveAccount(http.apiIdentity, account)
            snapshots.saveWallet(http.apiIdentity, account.id, wallet)
            val policy =
                RemoteConfiguration(
                    "ui-v1",
                    AuthenticationMethods(true, false),
                    AuthenticationMethods(true, false),
                    PaymentFeatures.AllEnabled,
                )
            database
                .configuration()
                .save(
                    AppConfigurationEntity.fromConfiguration(
                        http.apiIdentity,
                        policy,
                        "\"ui-v1\"",
                        clock.millis(),
                    ),
                )
        }
        database.close()
        database = Room.databaseBuilder(context, AxiaDatabase::class.java, file).build()
        var offline = true
        val authentication =
            object : AuthenticationRepository by HttpAuthenticationRepository(http, clock) {
                /** Hold only the remote port; cached Room reads remain independently available. */
                override suspend fun currentAccount(accessToken: String): AccountIdentity {
                    gate.await()
                    if (offline) throw AuthenticationFailure(AccountAccessIssue.Network)
                    return account
                }
            }
        val wallets =
            object : WalletRepository by HttpWalletRepository(http) {
                /** Only a later confirmed response can replace the saved balance. */
                override suspend fun currentWallet(accessToken: String, currency: String) =
                    wallet.copy(
                        availableMinorUnits = BigInteger("25000"),
                        totalMinorUnits = BigInteger("27500"),
                    )
            }
        val sessions =
            object : AuthenticationSessionStore {
                private var value: StoredAuthenticationSession? =
                    StoredAuthenticationSession(
                        "a".repeat(43),
                        clock.instant().plusSeconds(3600),
                        http.apiIdentity,
                        account.id,
                    )

                /** Return only the test's captured protected session. */
                override suspend fun read() = value

                /** Replace only this isolated test record. */
                override suspend fun write(session: StoredAuthenticationSession) {
                    value = session
                }

                /** Preserve a newer test session during compare-and-clear. */
                override suspend fun clear(expected: StoredAuthenticationSession) {
                    if (value == expected) value = null
                }
            }
        val configuration =
            object : RemoteConfigRepository {
                /** Observe the real persisted policy without network availability. */
                override suspend fun readCached() =
                    database.configuration().read(http.apiIdentity)?.toConfiguration()

                /** Keep refresh failure distinct from the saved policy. */
                override suspend fun refresh(): RemoteConfiguration =
                    throw RemoteConfigFailure(RemoteConfigIssue.Network)
            }
        lateinit var vm: AuthViewModel
        compose.runOnUiThread {
            vm =
                AuthViewModel(
                    SavedStateHandle(),
                    ServerAccountAccess(
                        authentication,
                        sessions,
                        clock,
                        wallets,
                        DashboardRepositoryImpl(database, clock),
                    ),
                    clock,
                    LoadRemoteConfiguration(configuration),
                )
            owner.put("auth", vm)
        }
        compose.setContent {
            val auth by vm.uiState.collectAsState()
            val session = remember { DemoSessionSeed.create(LocalDate.now()).copy(offline = true) }
            AxiapayTheme(offline = true) {
                AxiaApp(AxiaUiState(session, NavigationState()), {}, homeWallet = auth.homeWallet)
            }
        }
        compose.waitUntil(10000) { vm.uiState.value.account != null }
        compose.onNodeWithTag("home-wallet-balance").assertTextEquals("₦125.00")
        assertTrue(vm.uiState.value.isProcessing)
        gate.complete(Unit)
        compose.waitUntil(10000) { !vm.uiState.value.isProcessing }
        compose.onNodeWithTag("home-wallet-balance").assertTextEquals("₦125.00")
        assertEquals(AuthError.Network, vm.uiState.value.error)
        compose.runOnUiThread {
            offline = false
            vm.accept(AuthIntent.RefreshSession)
        }
        compose.waitUntil(10000) {
            !vm.uiState.value.isProcessing && vm.uiState.value.error == null
        }
        compose.onNodeWithTag("home-wallet-balance").assertTextEquals("₦250.00")
        runBlocking {
            assertEquals(
                BigInteger("25000"),
                DashboardRepositoryImpl(database, clock)
                    .read(http.apiIdentity, account.id)!!
                    .wallet!!
                    .availableMinorUnits,
            )
        }
    }
}
