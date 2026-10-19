package com.kesofty.axiapay

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.dashboard.DashboardRepositoryImpl
import com.kesofty.axiapay.data.database.*
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.config.*
import com.kesofty.axiapay.domain.wallet.*
import java.io.File
import java.math.BigInteger
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real SQLite persistence, foreign keys, account isolation and lossless monetary storage. */
class DashboardDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "room-tests/${UUID.randomUUID()}")
    private lateinit var database: AxiaDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)
    private val api = "https://api.example.test/api/v1/"
    private val account =
        AccountIdentity(
            "account-1",
            AccountContact(AuthContactType.Email, "fixture@example.test"),
            clock.instant(),
        )
    private val wallet =
        WalletSummary(
            "wallet-1",
            "NGN",
            WalletStatus.Active,
            BigInteger("9223372036854775808"),
            BigInteger("2500"),
            BigInteger("9223372036854778308"),
        )

    /** Open a uniquely owned no-backup database without touching the customer's installed data. */
    @Before
    fun open() {
        directory.mkdirs()
        database = newDatabase()
    }

    /** Close all connections before removing only this test's database files. */
    @After
    fun close() {
        database.close()
        directory.deleteRecursively()
    }

    /** Reopen the same database file to exercise exported-schema validation and durable reads. */
    private fun newDatabase() =
        Room.databaseBuilder(
                context,
                AxiaDatabase::class.java,
                File(directory, "dashboard.db").absolutePath,
            )
            .build()

    /** Access persistence through the same repository as production account workflows. */
    private fun repository() = DashboardRepositoryImpl(database, clock)

    /** Amounts beyond signed Long remain exact across database and repository recreation. */
    @Test
    fun recordsSurviveDatabaseReopeningWithoutMonetaryRounding() = runBlocking {
        repository().saveAccount(api, account)
        repository().saveWallet(api, account.id, wallet)
        val configuration =
            RemoteConfiguration(
                "v1",
                AuthenticationMethods(true, false),
                AuthenticationMethods(false, true),
                PaymentFeatures.AllEnabled,
            )
        database
            .configuration()
            .save(
                AppConfigurationEntity.fromConfiguration(
                    api,
                    configuration,
                    "\"v1\"",
                    clock.millis(),
                ),
            )
        database.close()
        database = newDatabase()
        val restored = requireNotNull(repository().read(api, account.id))
        assertEquals(account, restored.account)
        assertEquals(wallet, restored.wallet)
        assertEquals(configuration, database.configuration().read(api)!!.toConfiguration())
        assertEquals("\"v1\"", database.configuration().read(api)!!.etag)
        assertTrue(File(directory, "dashboard.db").isFile)
    }

    /** Account upserts must not use REPLACE, which would cascade-delete their existing wallets. */
    @Test
    fun refreshingIdentityKeepsItsWalletSnapshot() = runBlocking {
        repository().saveAccount(api, account)
        repository().saveWallet(api, account.id, wallet)
        val updated =
            account.copy(contact = AccountContact(AuthContactType.Email, "updated@example.test"))
        repository().saveAccount(api, updated)
        assertEquals(updated, repository().read(api, account.id)!!.account)
        assertEquals(wallet, repository().read(api, account.id)!!.wallet)
    }

    /** Cascading logout deletion preserves other accounts, other APIs and public configuration. */
    @Test
    fun accountAndApiScopesRemainIsolatedDuringDeletion() = runBlocking {
        val otherApi = "https://staging.example.test/api/v1/"
        val otherAccount = account.copy(id = "account-2")
        for (scope in listOf(api, otherApi)) {
            repository().saveAccount(scope, account)
            repository().saveWallet(scope, account.id, wallet)
        }
        repository().saveAccount(api, otherAccount)
        repository().saveWallet(api, otherAccount.id, wallet.copy(id = "wallet-2"))
        val configuration =
            RemoteConfiguration(
                "v1",
                AuthenticationMethods(true, false),
                AuthenticationMethods(true, false),
            )
        database
            .configuration()
            .save(
                AppConfigurationEntity.fromConfiguration(
                    api,
                    configuration,
                    "\"v1\"",
                    clock.millis(),
                ),
            )
        repository().clear(api, account.id)
        assertNull(repository().read(api, account.id))
        assertNull(database.dashboard().wallet(api, account.id, "NGN"))
        assertEquals(wallet, repository().read(otherApi, account.id)!!.wallet)
        assertNotNull(repository().read(api, otherAccount.id))
        assertNotNull(database.configuration().read(api))
    }

    /** A 304 touches only the exact ETag and never replaces policy or creates a missing row. */
    @Test
    fun revalidationUpdatesOnlyTheMatchingPolicyTimestamp() = runBlocking {
        val configuration =
            RemoteConfiguration(
                "v1",
                AuthenticationMethods(true, false),
                AuthenticationMethods(false, true),
            )
        val saved = AppConfigurationEntity.fromConfiguration(api, configuration, "\"v1\"", 1000)
        database.configuration().save(saved)
        assertEquals(0, database.configuration().markChecked(api, "\"v2\"", 2000))
        assertEquals(saved, database.configuration().read(api))
        assertEquals(1, database.configuration().markChecked(api, "\"v1\"", 3000))
        assertEquals(saved.copy(checkedAtEpochMs = 3000), database.configuration().read(api))
    }
}
