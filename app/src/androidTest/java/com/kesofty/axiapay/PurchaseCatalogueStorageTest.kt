package com.kesofty.axiapay

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.dashboard.DashboardRepositoryImpl
import com.kesofty.axiapay.data.database.*
import com.kesofty.axiapay.data.purchases.PurchaseCatalogueRepositoryImpl
import com.kesofty.axiapay.data.purchases.local.RoomPurchaseCatalogueLocalDataSource
import com.kesofty.axiapay.data.purchases.remote.PurchaseCatalogueRemoteDataSource
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.wallet.data.local.RoomWalletLocalDataSource
import com.kesofty.axiapay.wallet.domain.*
import java.io.File
import java.math.BigInteger
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/**
 * Real Room persistence, upgrade and cancellation checks use synthetic data in test-owned
 * databases.
 */
class PurchaseCatalogueStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "catalogue-tests/${UUID.randomUUID()}")
    private val file = File(directory, "snapshots.db")
    private lateinit var database: AxiaDatabase
    private val owner = PurchaseOwner("account-fixture", "https://fixture.example.test/api/v1/")
    private val account =
        AccountIdentity(
            owner.accountId,
            AccountContact(AuthContactType.Email, "fixture@example.test"),
            Instant.parse("2026-10-16T12:00:00Z"),
        )
    private var instant = account.verifiedAt
    private val clock =
        object : Clock() {
            /** Use a controlled timestamp so unchanged writes are detectable. */
            override fun instant() = instant

            /** Fixtures use a fixed UTC zone. */
            override fun getZone(): ZoneId = ZoneOffset.UTC

            /** The tests never change zones. */
            override fun withZone(zone: ZoneId): Clock = this
        }
    private val wallet =
        WalletSummary(
            "wallet-fixture",
            "NGN",
            WalletStatus.Active,
            BigInteger("10000"),
            BigInteger.ZERO,
            BigInteger("10000"),
        )
    private val product =
        PurchaseProduct(
            "product-fixture",
            "MTN 1 GB weekly",
            "mtn",
            "NGN",
            BigInteger("50050"),
            BigInteger("50050"),
            BigInteger("50050"),
            BigInteger.ONE,
            BigInteger.ZERO,
            true,
            PurchaseService.Data,
            "1 GB",
            604800,
        )
    private var products = listOf(product)
    private var reads = 0
    private var failure: PurchaseIssue? = null
    private var gate: CompletableDeferred<Unit>? = null
    private val started = CompletableDeferred<Unit>()
    private val sessions =
        FixtureSessions(
            StoredAuthenticationSession(
                "synthetic-token",
                account.verifiedAt.plusSeconds(3600),
                owner.apiIdentity,
                owner.accountId,
            ),
        )
    private val remote =
        object : PurchaseCatalogueRemoteDataSource {
            override val apiIdentity = owner.apiIdentity

            /**
             * This synthetic port never contacts HTTP; it can reproduce a late or failed download.
             */
            override suspend fun products(
                accessToken: String,
                currency: String,
                service: PurchaseService,
            ): List<PurchaseProduct> {
                reads++
                val captured = products.toList()
                started.complete(Unit)
                gate?.let { withContext(NonCancellable) { it.await() } }
                failure?.let { throw PurchaseFailure(it) }
                return captured
            }
        }

    /** Give each test its own private database path. */
    @Before
    fun prepare() {
        directory.mkdirs()
    }

    /** Release only test-owned resources and files. */
    @After
    fun close() {
        gate?.complete(Unit)
        if (::database.isInitialized) database.close()
        directory.deleteRecursively()
    }

    /**
     * Open the production Room schema and seed the authenticated account required by its foreign
     * keys.
     */
    private suspend fun open(): PurchaseCatalogueRepositoryImpl {
        database =
            Room.databaseBuilder(context, AxiaDatabase::class.java, file.absolutePath).build()
        DashboardRepositoryImpl(database, clock).saveAccount(owner.apiIdentity, account)
        return repository()
    }

    /** Bind real cache coordination to the isolated transport and session. */
    private fun repository() =
        PurchaseCatalogueRepositoryImpl(
            remote,
            RoomPurchaseCatalogueLocalDataSource(database, clock),
            sessions,
            clock,
        )

    /** One complete download survives database reopening and subsequent network failure. */
    @Test
    fun completeCatalogueSurvivesRestartAndFailedRefresh() = runBlocking {
        var repository = open()
        repository.refresh(owner, "synthetic-token", "NGN", PurchaseService.Data)
        database.close()
        database =
            Room.databaseBuilder(context, AxiaDatabase::class.java, file.absolutePath).build()
        repository = repository()
        assertEquals(
            listOf(product),
            repository.read(owner, "NGN", PurchaseService.Data)!!.products,
        )
        assertEquals(1, reads)
        failure = PurchaseIssue.Network
        try {
            repository.refresh(owner, "synthetic-token", "NGN", PurchaseService.Data)
            fail()
        } catch (expected: PurchaseFailure) {
            assertEquals(PurchaseIssue.Network, expected.issue)
        }
        assertEquals(
            listOf(product),
            repository.read(owner, "NGN", PurchaseService.Data)!!.products,
        )
        assertNull(
            repository.read(owner.copy(accountId = "another-account"), "NGN", PurchaseService.Data),
        )
        assertNull(
            repository.read(
                owner.copy(apiIdentity = "https://another.example.test/api/v1/"),
                "NGN",
                PurchaseService.Data,
            ),
        )
        assertNull(repository.read(owner, "NGN", PurchaseService.Airtime))
    }

    /** Unchanged server responses do not rewrite catalogue, wallet or account rows. */
    @Test
    fun unchangedRefreshLeavesDatabaseContentAndTimestampsUntouched() = runBlocking {
        val repository = open()
        val dashboard = DashboardRepositoryImpl(database, clock)
        RoomWalletLocalDataSource(database, clock)
            .saveWallet(owner.apiIdentity, owner.accountId, wallet)
        repository.refresh(owner, "synthetic-token", "NGN", PurchaseService.Data)
        val before =
            database
                .purchaseCatalogues()
                .catalogue(owner.apiIdentity, owner.accountId, "NGN", PurchaseService.Data.name)
        val accountBefore = database.dashboard().account(owner.apiIdentity, owner.accountId)
        val walletBefore = database.dashboard().wallet(owner.apiIdentity, owner.accountId, "NGN")
        instant = instant.plusSeconds(60)
        repository.refresh(owner, "synthetic-token", "NGN", PurchaseService.Data)
        dashboard.saveAccount(owner.apiIdentity, account)
        RoomWalletLocalDataSource(database, clock)
            .saveWallet(owner.apiIdentity, owner.accountId, wallet)
        assertEquals(
            before,
            database
                .purchaseCatalogues()
                .catalogue(owner.apiIdentity, owner.accountId, "NGN", PurchaseService.Data.name),
        )
        assertEquals(
            accountBefore,
            database.dashboard().account(owner.apiIdentity, owner.accountId),
        )
        assertEquals(
            walletBefore,
            database.dashboard().wallet(owner.apiIdentity, owner.accountId, "NGN"),
        )
        products = listOf(product.copy(isSubmissionAvailable = false))
        repository.refresh(owner, "synthetic-token", "NGN", PurchaseService.Data)
        assertFalse(
            repository
                .read(owner, "NGN", PurchaseService.Data)!!
                .products
                .single()
                .isSubmissionAvailable,
        )
        assertNotEquals(
            before,
            database
                .purchaseCatalogues()
                .catalogue(owner.apiIdentity, owner.accountId, "NGN", PurchaseService.Data.name),
        )
    }

    /**
     * Full withdrawals are atomic and retain an explicit empty snapshot rather than a false cache
     * miss.
     */
    @Test
    fun largeCatalogueCanBeReplacedByAnEmptyCatalogue() = runBlocking {
        val repository = open()
        products = (1..1000).map { product.copy(id = "product-$it") }
        repository.refresh(owner, "synthetic-token", "NGN", PurchaseService.Data)
        assertEquals(1000, repository.read(owner, "NGN", PurchaseService.Data)!!.products.size)
        products = emptyList()
        repository.refresh(owner, "synthetic-token", "NGN", PurchaseService.Data)
        assertEquals(
            emptyList<PurchaseProduct>(),
            repository.read(owner, "NGN", PurchaseService.Data)!!.products,
        )
    }

    /** A response completing after logout cannot resurrect removed account or catalogue rows. */
    @Test
    fun logoutRejectsLateCacheWrites() = runBlocking {
        val repository = open()
        gate = CompletableDeferred()
        val pending =
            async(Dispatchers.IO) {
                try {
                    repository.refresh(owner, "synthetic-token", "NGN", PurchaseService.Data)
                    null
                } catch (failure: PurchaseFailure) {
                    failure.issue
                }
            }
        try {
            started.await()
            sessions.value = null
            DashboardRepositoryImpl(database, clock).clear(owner.apiIdentity, owner.accountId)
        } finally {
            gate!!.complete(Unit)
        }
        assertEquals(PurchaseIssue.SessionChanged, pending.await())
        assertNull(repository.read(owner, "NGN", PurchaseService.Data))
        assertNull(database.dashboard().account(owner.apiIdentity, owner.accountId))
    }

    /**
     * Upgrade the actual exported version-one schema without deleting its account or wallet
     * snapshots.
     */
    @Test
    fun versionOneDatabaseMigratesWithoutLosingExistingSnapshots() = runBlocking {
        val schema =
            JSONObject(
                    InstrumentationRegistry.getInstrumentation()
                        .context
                        .assets
                        .open("room-v1.json")
                        .bufferedReader()
                        .use { it.readText() },
                )
                .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                old.execSQL(
                    entity
                        .getString("createSql")
                        .replace("${'$'}{TABLE_NAME}", entity.getString("tableName")),
                )
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (position in 0 until indices.length()) {
                    old.execSQL(
                        indices
                            .getJSONObject(position)
                            .getString("createSql")
                            .replace("${'$'}{TABLE_NAME}", entity.getString("tableName")),
                    )
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            old.execSQL(
                "INSERT INTO accounts VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    owner.apiIdentity,
                    owner.accountId,
                    "email",
                    "fixture@example.test",
                    instant.toEpochMilli(),
                    instant.toEpochMilli(),
                ),
            )
            old.execSQL(
                "INSERT INTO wallet_summaries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    owner.apiIdentity,
                    owner.accountId,
                    wallet.id,
                    "NGN",
                    "Active",
                    "10000",
                    "0",
                    "10000",
                    instant.toEpochMilli(),
                ),
            )
            old.version = 1
        }
        database =
            Room.databaseBuilder(context, AxiaDatabase::class.java, file.absolutePath).build()
        val restored =
            DashboardRepositoryImpl(database, clock).read(owner.apiIdentity, owner.accountId)!!
        assertEquals(account, restored.account)
        assertEquals(wallet, restored.wallet)
        assertNull(repository().read(owner, "NGN", PurchaseService.Data))
    }

    /**
     * Small protected-session fixture supports the same conditional ownership operations as
     * production.
     */
    private class FixtureSessions(@Volatile var value: StoredAuthenticationSession?) :
        AuthenticationSessionStore {
        /** Return the isolated current session. */
        override suspend fun read() = value

        /** Replace only this fixture's session record. */
        override suspend fun write(session: StoredAuthenticationSession) {
            value = session
        }

        /** Compare before replacing so an older operation cannot overwrite a newer login. */
        override suspend fun replace(
            expected: StoredAuthenticationSession,
            replacement: StoredAuthenticationSession,
        ): Boolean {
            if (value != expected) return false
            value = replacement
            return true
        }

        /** Clear only the expected record. */
        override suspend fun clear(expected: StoredAuthenticationSession) {
            if (value == expected) value = null
        }
    }
}
