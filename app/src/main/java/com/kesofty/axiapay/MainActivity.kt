package com.kesofty.axiapay

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.domain.purchases.PurchaseService
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.auth.AuthIntent
import com.kesofty.axiapay.presentation.auth.AuthScreen
import com.kesofty.axiapay.presentation.auth.AuthViewModel
import com.kesofty.axiapay.presentation.purchases.*
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.auth.AuthScreen
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * Hosts independent payment-preview and account-access owners and collects their lifecycle-aware
 * state.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Restores each owner without resubmitting commands when composition restarts. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val viewModel = ViewModelProvider(this)[AxiaViewModel::class.java]
        val authViewModel = ViewModelProvider(this)[AuthViewModel::class.java]
        val purchaseViewModel = ViewModelProvider(this)[PurchaseViewModel::class.java]
        setContent {
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
                when (authState.screen) {
                    AuthScreen.Dashboard ->
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
}

/** Shows the existing home screen independently of the account-entry route. */
@Preview(showBackground = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun AxiaHomePreview() {
    val state = AxiaUiState(DemoSessionSeed.create(LocalDate.now()), NavigationState())
    AxiapayTheme { AxiaApp(state, {}) }
}
