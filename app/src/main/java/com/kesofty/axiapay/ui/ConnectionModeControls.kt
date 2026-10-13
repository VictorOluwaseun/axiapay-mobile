package com.kesofty.axiapay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.domain.ConnectionMode
import com.kesofty.axiapay.presentation.AxiaIntent
import com.kesofty.axiapay.ui.theme.*

/** Renders session-scoped demo choices; selection is emitted through the existing MVI contract. */
@Composable
internal fun ConnectionModeControls(mode: ConnectionMode, onIntent: (AxiaIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connection preview", style = MaterialTheme.typography.titleMedium)
        Text(
            "Try either mode without changing your phone settings. Payments remain a demo.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Canvas)
                .border(controlBorder(), RoundedCornerShape(20.dp))
                .selectableGroup(),
        ) {
            ConnectionMode.entries.forEach { option ->
                ConnectionModeOption(option, mode == option) {
                    onIntent(AxiaIntent.SetConnectionMode(option))
                }
            }
        }
    }
}

/** Makes each complete row one accessible radio target, with readable wrapping descriptions. */
@Composable
private fun ConnectionModeOption(mode: ConnectionMode, selected: Boolean, select: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = select)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                when (mode) {
                    ConnectionMode.Automatic -> "Automatic"
                    ConnectionMode.ForceOffline -> "Force offline"
                    ConnectionMode.ForceOnline -> "Force online"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                when (mode) {
                    ConnectionMode.Automatic -> "Follow my internet connection"
                    ConnectionMode.ForceOffline -> "Greyscale UI + SMS demo"
                    ConnectionMode.ForceOnline -> "Full colour + online demo"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
    }
}

/** Describes effective connectivity without claiming a forced preview is a real network state. */
internal val ConnectionMode.statusTitle: String
    get() =
        when (this) {
            ConnectionMode.Automatic -> "Offline mode"
            ConnectionMode.ForceOffline -> "Offline preview"
            ConnectionMode.ForceOnline -> "Online preview"
        }
