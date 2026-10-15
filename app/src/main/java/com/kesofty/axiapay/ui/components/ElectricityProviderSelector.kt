package com.kesofty.axiapay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.presentation.ProviderPickerIntent
import com.kesofty.axiapay.presentation.ProviderPickerState
import com.kesofty.axiapay.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Renders the caller-owned selection, visibility and query. All edits use existing presentation
 * intents; choosing a row emits the original provider identity without submitting a payment.
 */
@Composable
fun ElectricityProviderSelector(
    options: List<String>,
    selected: String,
    state: ProviderPickerState,
    onPickerIntent: (ProviderPickerIntent) -> Unit,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val openLabel = stringResource(R.string.electricity_provider_change)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.electricity_provider_label),
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
        )
        Surface(
            modifier =
                Modifier.fillMaxWidth()
                    .testTag("electricity-provider-selector")
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClickLabel = openLabel) {
                        onPickerIntent(ProviderPickerIntent.Open)
                    },
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            border = BorderStroke(ControlBorderWidth, ControlOutline),
        ) {
            Row(
                Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderArtwork(selected, Modifier.width(44.dp))
                Text(
                    selected,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink,
                )
                AxiaIcon(Glyph.Chevron, Modifier.size(20.dp).rotate(90f), Pine)
            }
        }
    }
    if (!state.isOpen) return
    ElectricityProviderPicker(
        options,
        selected,
        state.query,
        { onPickerIntent(ProviderPickerIntent.Search(it)) },
        onSelect,
        { onPickerIntent(ProviderPickerIntent.Close) },
    )
}

/**
 * Owns framework dismissal animation only. The existing ViewModel retains search and form state;
 * choosing emits the provider before hiding, and dismissal never executes a payment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ElectricityProviderPicker(
    options: List<String>,
    selected: String,
    query: String,
    onQuery: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = close@{
        if (sheetState.targetValue == SheetValue.Hidden) return@close
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }
    val choose: (String) -> Unit = choose@{ provider ->
        if (sheetState.targetValue == SheetValue.Hidden) return@choose
        onSelect(provider)
        close()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 560.dp,
        containerColor = Color.White,
        contentColor = Ink,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Line) },
        modifier = Modifier.testTag("electricity-provider-picker"),
    ) {
        // Material's modal consumes system and IME insets; only the list scrolls below the search.
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            ElectricityProviderPickerContent(
                options,
                selected,
                query,
                onQuery,
                choose,
                close,
                sheetState.targetValue != SheetValue.Hidden,
            )
        }
    }
}

/** Renders a bounded, searchable list without owning payment details or triggering submission. */
@Composable
internal fun ElectricityProviderPickerContent(
    options: List<String>,
    selected: String,
    query: String,
    onQuery: (String) -> Unit,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    enabled: Boolean = true,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val matches =
        remember(options, query) { options.filter { matchesElectricityProvider(it, query) } }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.electricity_provider_label),
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
            )
            IconButton(
                {
                    keyboard?.hide()
                    onClose()
                },
                enabled = enabled,
            ) {
                AxiaIcon(
                    Glyph.Close,
                    description = stringResource(R.string.electricity_provider_close),
                )
            }
        }
        ElectricityProviderSearch(query, onQuery, enabled)
        when {
            matches.isEmpty() ->
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.electricity_provider_no_results),
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                    )
                    Text(
                        stringResource(R.string.electricity_provider_search_help),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                }
            else ->
                LazyColumn(
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .selectableGroup()
                        .testTag("electricity-provider-list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(matches, key = { it }) { provider ->
                        ElectricityProviderRow(provider, selected == provider, enabled) {
                            keyboard?.hide()
                            onSelect(provider)
                        }
                    }
                }
        }
    }
}

/** Keeps search visible above the list and provides explicit clear and keyboard-search actions. */
@Composable
private fun ElectricityProviderSearch(query: String, onQuery: (String) -> Unit, enabled: Boolean) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        query,
        onQuery,
        Modifier.fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag("electricity-provider-search"),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.electricity_provider_search)) },
        placeholder = { Text(stringResource(R.string.electricity_provider_search_hint)) },
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = { AxiaIcon(Glyph.Search, color = Muted) },
        trailingIcon = {
            if (query.isNotEmpty())
                IconButton({ onQuery("") }, enabled = enabled) {
                    AxiaIcon(
                        Glyph.Close,
                        description = stringResource(R.string.electricity_provider_clear_search),
                    )
                }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors =
            OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = ControlOutline,
                unfocusedContainerColor = Color.White,
            ),
    )
}

/** Exposes the full name and radio selection while keeping artwork small and purely decorative. */
@Composable
private fun ElectricityProviderRow(
    provider: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("electricity-provider-$provider")
                .clip(RoundedCornerShape(16.dp))
                .selectable(
                    selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onSelect,
                ),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Mint.copy(alpha = 0.35f) else Color.White,
        border = if (selected) controlBorder(true) else null,
    ) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProviderArtwork(provider, Modifier.width(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(provider, style = MaterialTheme.typography.bodyLarge, color = Ink)
                Text(
                    electricityProviderCode(provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            if (selected)
                Box(
                    Modifier.size(24.dp).background(Pine, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AxiaIcon(Glyph.Check, Modifier.size(15.dp), Color.White)
                }
        }
    }
}
