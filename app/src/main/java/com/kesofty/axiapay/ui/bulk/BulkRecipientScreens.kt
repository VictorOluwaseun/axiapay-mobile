package com.kesofty.axiapay.ui.bulk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.BulkLimits
import com.kesofty.axiapay.domain.BulkService
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.theme.*

/** A single service choice opens its retained draft immediately, without batch setup. */
@Composable
internal fun BulkServicePicker(state: BulkFlowState, onIntent: (BulkIntent) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            BulkHeading(
                stringResource(R.string.bulk_choose_heading),
                stringResource(R.string.bulk_choose_copy),
            )
        }
        items(BulkService.entries, key = { it.name }) { service ->
            val draft =
                state.draft?.takeIf { it.service == service }
                    ?: state.savedDrafts.find { it.draft.service == service }?.draft
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White)
                    .clickable(role = Role.Button) { onIntent(BulkIntent.ChooseService(service)) }
                    .padding(20.dp)
                    .testTag("bulk-service-${service.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconTile(serviceIcon(service), tint = Mint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(serviceLabel(service), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(
                            if (draft != null) R.string.bulk_resume_draft
                            else serviceDescription(service),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
                AxiaIcon(Glyph.Chevron, color = Pine)
            }
        }
    }
}

/** Maps picker descriptions to resources while payment-type rules remain outside the View. */
private fun serviceDescription(service: BulkService): Int =
    when (service) {
        BulkService.Airtime -> R.string.bulk_airtime_copy
        BulkService.Data -> R.string.bulk_data_copy
        BulkService.Electricity -> R.string.bulk_electricity_copy
        BulkService.TV -> R.string.bulk_tv_copy
    }

/** Search stays reachable while stable lazy rows and a single editor accommodate large lists. */
@Composable
internal fun BulkRecipients(
    state: BulkFlowState,
    offline: Boolean,
    onIntent: (BulkIntent) -> Unit,
) {
    val draft = state.draft ?: return
    val listState = rememberLazyListState()
    LaunchedEffect(state.editor?.id, state.recipientQuery) {
        listState.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth()
                .background(Canvas)
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    pluralStringResource(
                        R.plurals.bulk_recipient_count,
                        draft.recipients.size,
                        draft.recipients.size,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                TextButton(
                    { onIntent(BulkIntent.OpenImport) },
                    modifier = Modifier.testTag("bulk-import"),
                ) {
                    Text(stringResource(R.string.bulk_import_short))
                }
            }
            if (draft.recipients.isNotEmpty() && state.editor == null) {
                BulkRecipientSearch(state.recipientQuery) {
                    onIntent(BulkIntent.SearchRecipients(it))
                }
                RecipientListActions(state, onIntent)
            }
            if (state.selectingRecipients)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton({ onIntent(BulkIntent.SelectVisibleRecipients) }) {
                        Text(stringResource(R.string.bulk_select_visible))
                    }
                    TextButton(
                        { onIntent(BulkIntent.RequestRemoveSelected) },
                        enabled = state.selectedRecipientIds.isNotEmpty(),
                    ) {
                        Text(
                            stringResource(
                                R.string.bulk_remove_selected,
                                state.selectedRecipientIds.size,
                            ),
                            color = Danger,
                        )
                    }
                }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).testTag("bulk-recipients-list"),
        ) {
            if (offline)
                item(key = "offline") { BulkNotice(stringResource(R.string.bulk_offline_draft)) }
            if (state.editor != null) bulkEditorItems(state, onIntent)
            if (state.editor != null || draft.recipients.isEmpty())
                item(key = "add") {
                    OutlinedButton(
                        { onIntent(BulkIntent.AddAnother) },
                        enabled = draft.recipients.size < BulkLimits.MAX_RECIPIENTS,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(18.dp),
                        border = controlBorder(),
                    ) {
                        AxiaIcon(Glyph.Plus, Modifier.size(20.dp), Pine)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.bulk_add_recipient),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(
                        stringResource(R.string.bulk_defaults_hint),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
            if (draft.recipients.isEmpty())
                item(key = "samples") {
                    TextButton(
                        { onIntent(BulkIntent.LoadSamples) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.bulk_import_samples))
                    }
                }
            if (state.recipientQuery.isNotBlank())
                item(key = "matches") {
                    BulkResultCount(state.visibleRecipients.size, draft.recipients.size)
                }
            if (state.visibleRecipients.isEmpty() && draft.recipients.isNotEmpty())
                item(key = "empty") { BulkNotice(stringResource(R.string.bulk_no_search_results)) }
            items(state.visibleRecipients, key = { it.input.id }, contentType = { "recipient" }) {
                row ->
                if (row.input.id != state.editor?.id)
                    CompactRecipient(
                        row,
                        state.selectingRecipients,
                        row.input.id in state.selectedRecipientIds,
                        onIntent,
                    )
            }
            if (state.showErrors)
                items(state.validation?.issues.orEmpty().filter { it.recipientId == null }) {
                    BulkNotice(stringResource(it.kind.messageResource), true)
                }
        }
    }
    if (state.confirmingRemoval)
        AlertDialog(
            onDismissRequest = { onIntent(BulkIntent.RemoveSelected(false)) },
            title = {
                Text(stringResource(R.string.bulk_remove_selected, state.selectedRecipientIds.size))
            },
            text = { Text(stringResource(R.string.bulk_remove_selected_copy)) },
            confirmButton = {
                TextButton({ onIntent(BulkIntent.RemoveSelected(true)) }) {
                    Text(stringResource(R.string.bulk_remove), color = Danger)
                }
            },
            dismissButton = {
                TextButton({ onIntent(BulkIntent.RemoveSelected(false)) }) {
                    Text(stringResource(R.string.bulk_keep_recipients))
                }
            },
        )
}

/** Keeps select/add actions reachable without scrolling through the recipients. */
@Composable
private fun RecipientListActions(state: BulkFlowState, emit: (BulkIntent) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton({ emit(BulkIntent.ToggleRecipientSelection) }) {
            Text(
                stringResource(
                    if (state.selectingRecipients) R.string.bulk_selection_done
                    else R.string.bulk_select,
                ),
            )
        }
        if (!state.selectingRecipients)
            TextButton(
                { emit(BulkIntent.AddAnother) },
                enabled = (state.draft?.recipients?.size ?: 0) < BulkLimits.MAX_RECIPIENTS,
            ) {
                AxiaIcon(Glyph.Plus, Modifier.size(18.dp), Pine)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.bulk_add_short))
            }
    }
}

/** Each compact row gets its terms directly; selection never searches or mutates the full list. */
@Composable
private fun CompactRecipient(
    row: BulkRecipientRow,
    selecting: Boolean,
    selected: Boolean,
    emit: (BulkIntent) -> Unit,
) {
    val recipient = row.input
    val label = recipient.name.ifBlank { recipient.recipientReference }
    val editLabel = stringResource(R.string.bulk_edit_recipient, label)
    val action =
        if (selecting)
            Modifier.toggleable(selected, role = Role.Checkbox) {
                emit(BulkIntent.SelectRecipient(recipient.id))
            }
        else
            Modifier.clickable(role = Role.Button, onClickLabel = editLabel) {
                emit(BulkIntent.EditRecipient(recipient.id))
            }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .then(action)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)
            .testTag("bulk-row-${row.ordinal}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selecting) Checkbox(selected, onCheckedChange = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (recipient.name.isNotBlank())
                Text(
                    recipient.recipientReference,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    listOf(recipient.provider, recipient.product)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                Text(
                    row.amountMinorUnits?.let(::bulkMoney) ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = Pine,
                )
            }
        }
        if (!selecting)
            IconButton({ emit(BulkIntent.EditRecipient(recipient.id)) }) {
                AxiaIcon(Glyph.Chevron, description = editLabel)
            }
    }
}

/**
 * Separate lazy items keep every input reachable above the keyboard on compact and enlarged
 * screens.
 */
private fun LazyListScope.bulkEditorItems(state: BulkFlowState, emit: (BulkIntent) -> Unit) {
    val editor = state.editor ?: return
    val draft = state.draft ?: return
    val ordinal =
        draft.recipients.indexOfFirst { it.id == editor.id }.takeIf { it >= 0 }?.plus(1)
            ?: (draft.recipients.size + 1)
    item(key = "${editor.id}-heading") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.bulk_recipient_number, ordinal),
                style = MaterialTheme.typography.titleMedium,
                color = Pine,
                modifier = Modifier.weight(1f),
            )
            TextButton({ emit(BulkIntent.SaveRecipient) }) {
                Text(stringResource(R.string.bulk_editor_done))
            }
            IconButton({ emit(BulkIntent.RemoveRecipient(editor.id)) }) {
                AxiaIcon(Glyph.Close, description = stringResource(R.string.bulk_remove_current))
            }
        }
    }
    item(key = "${editor.id}-provider") {
        val selectProvider: (String) -> Unit = {
            emit(BulkIntent.RecipientValue(editor.copy(provider = it)))
        }
        when (editor.service) {
            BulkService.Electricity ->
                ElectricityProviderSelector(
                    state.editorProviders,
                    editor.provider,
                    state.providerPicker,
                    onPickerIntent = { emit(BulkIntent.ProviderPicker(it)) },
                    onSelect = selectProvider,
                )
            else ->
                ProviderChoices(
                    state.editorProviders,
                    editor.provider,
                    showInternationalAirtime = state.showsInternationalAirtime,
                    onSelect = selectProvider,
                )
        }
    }
    item(key = "${editor.id}-reference") {
        val label =
            when (editor.service) {
                BulkService.Electricity -> R.string.bulk_meter
                BulkService.TV -> R.string.bulk_smartcard
                else -> R.string.bulk_phone
            }
        BulkTextField(
            editor.recipientReference,
            { emit(BulkIntent.RecipientValue(editor.copy(recipientReference = it))) },
            stringResource(label),
            "bulk-recipient-reference",
            KeyboardType.Phone,
        )
    }
    if (state.editorProducts.isNotEmpty())
        item(key = "${editor.id}-product") {
            val label =
                when (editor.service) {
                    BulkService.Electricity -> R.string.bulk_account_type
                    BulkService.TV -> R.string.bulk_tv_package
                    else -> R.string.bulk_data_plan
                }
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            BulkChoices(state.editorProducts, editor.product) {
                emit(BulkIntent.RecipientValue(editor.copy(product = it)))
            }
        }
    item(key = "${editor.id}-amount") {
        if (editor.service in listOf(BulkService.Data, BulkService.TV)) {
            InfoRow(
                stringResource(R.string.bulk_sample_price),
                state.editorAmountMinorUnits?.let(::bulkMoney) ?: "—",
            )
        } else {
            BulkTextField(
                editor.amount,
                { emit(BulkIntent.RecipientValue(editor.copy(amount = it))) },
                stringResource(R.string.bulk_amount),
                "bulk-recipient-amount",
                KeyboardType.Decimal,
            )
        }
    }
    state.editorIssue?.let { issue ->
        item(key = "${editor.id}-issue") { BulkNotice(stringResource(issue.messageResource), true) }
    }
}

/** Wraps product choices instead of hiding options offscreen at larger font sizes. */
@Composable
private fun BulkChoices(choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { value ->
            FilterChip(
                selected == value,
                { onSelect(value) },
                { Text(value) },
                border = controlBorder(selected == value),
            )
        }
    }
}

/** Emits raw input without submitting from the keyboard; payment confirmation remains explicit. */
@Composable
private fun BulkTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    keyboard: KeyboardType,
) {
    OutlinedTextField(
        value,
        onChange,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
        colors =
            OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = ControlOutline,
                unfocusedContainerColor = Color.White,
            ),
    )
}
