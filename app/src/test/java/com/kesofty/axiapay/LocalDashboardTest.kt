package com.kesofty.axiapay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.auth.ServerAccountAccess
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.wallet.*
import com.kesofty.axiapay.presentation.HomeWalletState
import com.kesofty.axiapay.presentation.auth.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/**
 * Local-first startup, identity isolation and refresh failure behavior with controlled remote
 * ports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalDashboardTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val api = RecordingAuthenticationRepository(now)
    private val sessions = MemoryAuthenticationSessionStore()
    private val wallets = RecordingWalletRepository()
    private val snapshots = InMemoryDashboardRepository()
    private val access = ServerAccountAccess(api, sessions, clock, wallets, snapshots)
    private val owner = ViewModelStore()

    /** Let tests suspend only at explicitly gated network calls. */
    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Cancel presentation owners before resetting the test dispatcher. */
    @After
    fun close() {
        owner.clear()
        Dispatchers.resetMain()
    }

    /** Simulate a prior successful online session and exact persisted dashboard. */
    private suspend fun seed() {
        sessions.value =
            StoredAuthenticationSession(
                api.session.accessToken,
                api.session.expiresAt,
                api.apiIdentity,
                api.session.account.id,
            )
        snapshots.saveAccount(api.apiIdentity, api.session.account)
        snapshots.saveWallet(api.apiIdentity, api.session.account.id, wallets.wallet)
    }

    /** Local reads never invoke authentication or wallet HTTP. */
    @Test
    fun cachedSnapshotLoadsWithoutNetwork() = runTest {
        seed()
        assertEquals(api.session.account, access.cached()!!.account)
        assertEquals(WalletLookupResult.Loaded(wallets.wallet), access.cached()!!.wallet)
        assertEquals(0, api.identityRequests)
        assertEquals(0, wallets.requests)
    }

    /**
     * An expired credential, unknown identity, or other API cannot unlock stored display records.
     */
    @Test
    fun expiredMissingAndOtherOriginSessionsCannotReadCachedAccounts() = runTest {
        seed()
        val saved = requireNotNull(sessions.value)
        for (session in
            listOf(
                saved.copy(expiresAt = now),
                saved.copy(accountId = "another-account"),
                saved.copy(apiIdentity = "https://another.example/api/v1/"),
                saved.copy(accountId = null),
            )) {
            sessions.value = session
            assertNull(access.cached())
        }
        assertEquals(0, api.identityRequests)
    }

    /** The actual reducer shows its saved balance while an HTTP wallet refresh is suspended. */
    @Test
    fun dashboardReadsLocalBeforeRefreshAndKeepsBalanceOnNetworkFailure() = runTest {
        seed()
        val old = wallets.wallet
        val gate = CompletableDeferred<Unit>()
        wallets.gate = gate
        val vm = AuthViewModel(SavedStateHandle(), access, clock, enabledRemoteConfiguration())
        owner.put("auth", vm)
        try {
            assertEquals(AuthScreen.Dashboard, vm.uiState.value.screen)
            assertEquals(HomeWalletState.Loaded(old), vm.uiState.value.homeWallet)
            assertTrue(vm.uiState.value.isProcessing)
            wallets.failure = WalletFailure(WalletIssue.Network)
            gate.complete(Unit)
            runCurrent()
            assertFalse(vm.uiState.value.isProcessing)
            assertEquals(HomeWalletState.Loaded(old), vm.uiState.value.homeWallet)
        } finally {
            gate.complete(Unit)
            owner.clear()
        }
    }

    /**
     * A failed identity refresh preserves the committed dashboard and its safe error
     * classification.
     */
    @Test
    fun offlineRestartKeepsCachedAccountAndWallet() = runTest {
        seed()
        api.failure = AuthenticationFailure(AccountAccessIssue.Network)
        val vm = AuthViewModel(SavedStateHandle(), access, clock, enabledRemoteConfiguration())
        owner.put("auth", vm)
        try {
            assertEquals(AuthScreen.Dashboard, vm.uiState.value.screen)
            assertEquals(HomeWalletState.Loaded(wallets.wallet), vm.uiState.value.homeWallet)
            assertEquals(AuthError.Network, vm.uiState.value.error)
            assertNotNull(sessions.value)
        } finally {
            owner.clear()
        }
    }

    /** Authoritative session revocation removes both display ownership and its wallet. */
    @Test
    fun revokedSessionClearsCachedData() = runTest {
        seed()
        api.failure = AuthenticationFailure(AccountAccessIssue.InvalidCredentials)
        assertEquals(AccountAccessResult.SignedOut, access.restore())
        assertNull(access.cached())
        assertNull(snapshots.read(api.apiIdentity, api.session.account.id))
    }

    /**
     * An explicit missing-wallet response invalidates old funds without deleting account identity.
     */
    @Test
    fun missingWalletDoesNotKeepAnOldBalance() = runTest {
        seed()
        wallets.failure = WalletFailure(WalletIssue.Missing)
        val result = access.restore() as AccountAccessResult.SignedIn
        assertEquals(WalletLookupResult.Failed(WalletIssue.Missing), result.wallet)
        assertNull(snapshots.read(api.apiIdentity, api.session.account.id)!!.wallet)
    }

    /**
     * Successful logout clears local owned snapshots; failed remote revocation preserves them for
     * recovery.
     */
    @Test
    fun logoutClearsOnlyAfterSuccessfulRevocation() = runTest {
        seed()
        api.failure = AuthenticationFailure(AccountAccessIssue.Network)
        try {
            access.signOut()
            fail()
        } catch (expected: AuthenticationFailure) {
            assertNotNull(access.cached())
        }
        api.failure = null
        assertEquals(AccountAccessResult.SignedOut, access.signOut())
        assertNull(snapshots.read(api.apiIdentity, api.session.account.id))
    }

    /** An unexpected authenticated identity cannot overwrite the account bound to this session. */
    @Test
    fun mismatchedIdentityCannotReplaceCachedOwner() = runTest {
        seed()
        val account = api.session.account
        api.session = api.session.copy(account = account.copy(id = "another-account"))
        try {
            access.restore()
            fail()
        } catch (expected: AuthenticationFailure) {
            assertEquals(AccountAccessIssue.InvalidResponse, expected.issue)
        }
        assertEquals(account, snapshots.read(api.apiIdentity, account.id)!!.account)
        assertNull(snapshots.read(api.apiIdentity, "another-account"))
    }
}
