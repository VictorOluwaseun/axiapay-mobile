package com.kesofty.axiapay

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.auth.AuthIntent
import com.kesofty.axiapay.presentation.auth.AuthScreen
import com.kesofty.axiapay.presentation.auth.AuthViewModel
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.auth.AuthScreen
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate

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
            AxiapayTheme(offline = state.session.offline) {
                when (authState.screen) {
                    AuthScreen.Dashboard ->
                        AxiaApp(
                            state,
                            viewModel::accept,
                            homeWallet = authState.homeWallet,
                            onRefreshWallet = { authViewModel.accept(AuthIntent.RetryWallet) },
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
