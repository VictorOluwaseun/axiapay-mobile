package com.kesofty.axiapay

import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.auth.*
import com.kesofty.axiapay.domain.wallet.*
import com.kesofty.axiapay.presentation.HomeWalletState
import com.kesofty.axiapay.presentation.auth.AuthUiState
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Enforces source isolation before any dashboard View receives a render state. */
class HomeWalletStateTest {
    private val wallet = RecordingWalletRepository().wallet
    private val account =
        AccountIdentity(
            "account",
            AccountContact(AuthContactType.Email, "synthetic@example.test"),
            Instant.EPOCH,
        )

    /**
     * Demo composition continues to select its own wallet even when unrelated server data is
     * present.
     */
    @Test
    fun demoModeAlwaysSelectsTheDemoRenderer() {
        assertEquals(
            HomeWalletState.Demo,
            AuthUiState(
                    mode = AccountAccessMode.Demo,
                    account = account,
                    wallet = WalletLookupResult.Loaded(wallet),
                )
                .homeWallet,
        )
    }

    /**
     * Cleared identities cannot expose a previously loaded wallet or silently select demo money.
     */
    @Test
    fun signedOutServerStatesIgnoreOldWalletValues() {
        val state =
            AuthUiState(mode = AccountAccessMode.Server, wallet = WalletLookupResult.Loaded(wallet))
        assertEquals(HomeWalletState.SignedOut, state.homeWallet)
    }

    /**
     * Home consumes the exact available balance; reserved funds are not counted again as available.
     */
    @Test
    fun serverWalletValuesArePassedWithoutConversionsOrDemoFallback() {
        val state =
            AuthUiState(
                mode = AccountAccessMode.Server,
                account = account,
                wallet = WalletLookupResult.Loaded(wallet),
            )
        assertSame(wallet, (state.homeWallet as HomeWalletState.Loaded).wallet)
        assertEquals(
            "12500",
            (state.homeWallet as HomeWalletState.Loaded).wallet.availableMinorUnits.toString(),
        )
    }

    /** Loading never exposes an older amount while a current session/wallet request is pending. */
    @Test
    fun refreshingAndUnloadedWalletsHaveExplicitLoadingStates() {
        val state = AuthUiState(mode = AccountAccessMode.Server, account = account)
        assertEquals(HomeWalletState.Loading, state.homeWallet)
        assertEquals(
            HomeWalletState.Loading,
            state
                .copy(wallet = WalletLookupResult.Loaded(wallet), isWalletRefreshing = true)
                .homeWallet,
        )
    }

    /** Every wallet failure remains visible with its original safe classification. */
    @Test
    fun missingAndFailedReadsNeverSelectDemoBalances() {
        for (issue in WalletIssue.entries) {
            val state =
                AuthUiState(
                    mode = AccountAccessMode.Server,
                    account = account,
                    wallet = WalletLookupResult.Failed(issue),
                )
            assertEquals(HomeWalletState.Failed(issue), state.homeWallet)
        }
    }
}
