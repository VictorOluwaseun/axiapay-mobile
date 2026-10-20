package com.kesofty.axiapay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.purchases.PurchaseService
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.auth.AuthIntent
import com.kesofty.axiapay.presentation.auth.AuthScreen
import com.kesofty.axiapay.presentation.auth.AuthViewModel
import com.kesofty.axiapay.presentation.navigation.AppDestination
import com.kesofty.axiapay.presentation.navigation.accountBackStack
import com.kesofty.axiapay.presentation.purchases.*
import com.kesofty.axiapay.ui.auth.AuthScreen
import com.kesofty.axiapay.ui.navigation.StateNavigationHost
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import kotlinx.coroutines.flow.first

/**
 * Compose composition boundary. Resolve all three Hilt owners before entering a NavHost so their
 * Activity lifetimes, SavedStateHandles, checkpoints, and cross-flow result delivery stay intact.
 * Screens receive immutable state and callbacks; navigation never submits a financial operation.
 */
@Composable
fun AxiaAppRoute(
    viewModel: AxiaViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
    purchaseViewModel: PurchaseViewModel = hiltViewModel(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, authViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                authViewModel.accept(AuthIntent.RefreshSession)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val purchaseState by purchaseViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(authState.paymentFeatures) {
        viewModel.accept(AxiaIntent.UpdateFeatures(authState.paymentFeatures))
        purchaseViewModel.accept(PurchaseIntent.UpdateFeatures(authState.paymentFeatures))
    }
    val purchaseForm =
        (state.navigation.sheet as? Sheet.Payment)?.form?.takeIf {
            it.isWalletPurchaseEntry
        }
    val purchaseService = purchaseForm?.purchaseService ?: PurchaseService.Airtime
    val purchaseRouteId =
        purchaseForm?.requestId.takeIf {
            authState.screen == AuthScreen.Dashboard &&
                !state.isRestoring &&
                !state.restorationFailed
        }
    val purchaseAccountId =
        authState.account?.id.takeIf { authState.screen == AuthScreen.Dashboard }
    LaunchedEffect(authState.screen, purchaseAccountId, purchaseRouteId, purchaseService) {
        // Foreground verification hides the dashboard but retains its in-memory visit.
        if (authState.screen in setOf(AuthScreen.Restoring, AuthScreen.SessionRecovery)) {
            return@LaunchedEffect
        }
        purchaseViewModel.accept(
            PurchaseIntent.Bind(purchaseAccountId, purchaseRouteId, purchaseService),
        )
    }
    LaunchedEffect(lifecycle, purchaseViewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            purchaseViewModel.effects.collect { effect ->
                when (effect) {
                    is PurchaseEffect.RefreshWallet -> {
                        val ready =
                            authViewModel.uiState.first {
                                it.screen != AuthScreen.Restoring &&
                                    (!it.isProcessing || it.account?.id != effect.accountId)
                            }
                        if (ready.account?.id == effect.accountId) {
                            authViewModel.accept(AuthIntent.RetryWallet)
                        }
                    }
                    is PurchaseEffect.Close -> {
                        val route =
                            (viewModel.uiState.value.navigation.sheet as? Sheet.Payment)
                                ?.form
                                ?.requestId
                        if (
                            authViewModel.uiState.value.account?.id == effect.accountId &&
                                route == effect.routeId &&
                                purchaseViewModel.isCurrentClose(effect)
                        ) {
                            viewModel.accept(AxiaIntent.CloseSheet)
                        }
                    }
                }
            }
        }
    }
    AxiapayTheme(offline = state.session.offline) {
        StateNavigationHost(
            destinations = AppDestination.accountRoutes,
            backStack = accountBackStack(authState),
            onBack = { authViewModel.accept(AuthIntent.Back) },
        ) { destination ->
            when (destination) {
                AppDestination.Dashboard ->
                    AxiaApp(
                        state,
                        viewModel::accept,
                        homeWallet = authState.homeWallet,
                        onRefreshWallet = { authViewModel.accept(AuthIntent.RetryWallet) },
                        purchases =
                            purchaseState
                                .forScope(purchaseAccountId, purchaseRouteId, purchaseService)
                                .takeIf {
                                    it.enabled
                                },
                        onPurchaseIntent = purchaseViewModel::accept,
                        authShortcutLabel =
                            if (authState.isDemo) null
                            else
                                stringResource(
                                    if (authState.account == null) R.string.auth_sign_in
                                    else R.string.auth_account_shortcut,
                                ),
                    ) {
                        authViewModel.accept(AuthIntent.OpenAccount)
                    }
                else -> AuthScreen(authState, state.session.offline, authViewModel::accept)
            }
        }
    }
}
