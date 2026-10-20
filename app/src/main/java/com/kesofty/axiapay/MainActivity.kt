package com.kesofty.axiapay

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kesofty.axiapay.data.DemoSessionSeed
import com.kesofty.axiapay.presentation.AxiaUiState
import com.kesofty.axiapay.presentation.NavigationState
import com.kesofty.axiapay.ui.AxiaApp
import com.kesofty.axiapay.ui.AxiaAppRoute
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate

/** Android host; Compose owns navigation and retrieves the existing Activity-owned Hilt models. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Install the Compose root without creating a second ViewModel or navigation owner. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent { AxiaAppRoute() }
    }
}

/** Shows the existing home screen independently of the account-entry route. */
@Preview(showBackground = true, device = "spec:width=412dp,height=915dp,dpi=420")
@Composable
private fun AxiaHomePreview() {
    val state = AxiaUiState(DemoSessionSeed.create(LocalDate.now()), NavigationState())
    AxiapayTheme { AxiaApp(state, {}) }
}
