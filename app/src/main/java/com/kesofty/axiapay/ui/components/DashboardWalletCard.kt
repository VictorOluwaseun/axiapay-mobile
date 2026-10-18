package com.kesofty.axiapay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.ui.theme.*

/** Shares the established dashboard design while callers own wallet data and explicit actions. */
@Composable
internal fun DashboardWalletCard(
    title: String,
    badge: String,
    hidden: Boolean,
    onToggleHidden: () -> Unit,
    onAddMoney: () -> Unit,
    onSendMoney: () -> Unit,
    canHideBalance: Boolean = true,
    actionsEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    WalletCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = Color(0xFFBDD7CF).modeColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(
                    onToggleHidden,
                    enabled = canHideBalance,
                    modifier = Modifier.size(48.dp).padding(start = 2.dp),
                ) {
                    AxiaIcon(
                        if (hidden) Glyph.EyeOff else Glyph.Eye,
                        Modifier.size(17.dp),
                        Color(0xFFBDD7CF).modeColor(),
                        stringResource(
                            if (hidden) R.string.home_wallet_show else R.string.home_wallet_hide,
                        ),
                    )
                }
            }
            Text(
                badge,
                color = Mint,
                style = MaterialTheme.typography.labelSmall,
                modifier =
                    Modifier.background(Color.White.copy(alpha = .08f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        content()
        Spacer(Modifier.height(22.dp))
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            FlowRow(
                maxItemsInEachRow = if (maxWidth.value / fontScale < 200f) 1 else 2,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onAddMoney,
                    modifier =
                        Modifier.weight(1f).heightIn(min = 48.dp).testTag("home-wallet-add-money"),
                    enabled = actionsEnabled,
                    shape = RoundedCornerShape(14.dp),
                    colors =
                        ButtonDefaults.buttonColors(containerColor = Mint, contentColor = DeepPine),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    AxiaIcon(Glyph.Plus, Modifier.size(18.dp), LocalContentColor.current)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(R.string.home_wallet_add_money),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                Button(
                    onSendMoney,
                    modifier =
                        Modifier.weight(1f).heightIn(min = 48.dp).testTag("home-wallet-send-money"),
                    enabled = actionsEnabled,
                    shape = RoundedCornerShape(14.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = .10f),
                            contentColor = Color.White,
                        ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    AxiaIcon(Glyph.ArrowUp, Modifier.size(17.dp), LocalContentColor.current)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(R.string.home_wallet_send_money),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Keeps supporting wallet copy and its status dot identical in demo and authenticated cards. */
@Composable
internal fun WalletCardCaption(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(Mint, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFBDD7CF).modeColor(),
            modifier = Modifier.weight(1f),
        )
    }
}
