package com.kesofty.axiapay

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.di.AxiaViewModelFactory
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import java.time.Clock
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val viewModel =
            ViewModelProvider(this, AxiaViewModelFactory(Clock.systemDefaultZone()))[
                AxiaViewModel::class.java]
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            AxiapayTheme(offline = state.session.offline) { AxiaApp(state, viewModel::accept) }
        }
    }
}

@Preview(showBackground = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun AxiaHomePreview() {
    val state = AxiaUiState(DemoSessionSeed.create(LocalDate.now()), NavigationState())
    AxiapayTheme { AxiaApp(state) {} }
}
