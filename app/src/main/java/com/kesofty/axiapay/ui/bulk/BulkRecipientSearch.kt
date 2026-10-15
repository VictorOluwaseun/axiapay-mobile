package com.kesofty.axiapay.ui.bulk

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*

/** Shared accessible search field emits presentation intents and never changes reviewed terms. */
@Composable
internal fun BulkRecipientSearch(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        query,
        onChange,
        modifier = Modifier.fillMaxWidth().testTag("bulk-recipient-search"),
        singleLine = true,
        label = { Text(stringResource(R.string.bulk_search)) },
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = { AxiaIcon(Glyph.Search, color = Muted) },
        trailingIcon = {
            if (query.isNotEmpty())
                IconButton({ onChange("") }) {
                    AxiaIcon(Glyph.Close, description = stringResource(R.string.bulk_clear_search))
                }
        },
        colors =
            OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = ControlOutline,
                unfocusedContainerColor = Color.White,
            ),
    )
}

/** A filtered count clarifies that searching does not change the full-batch payment total. */
@Composable
internal fun BulkResultCount(visible: Int, total: Int) {
    Text(
        pluralStringResource(R.plurals.bulk_showing_count, total, visible, total),
        style = MaterialTheme.typography.bodyMedium,
        color = Muted,
    )
}
