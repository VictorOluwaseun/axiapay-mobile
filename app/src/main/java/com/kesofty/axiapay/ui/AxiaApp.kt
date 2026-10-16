package com.kesofty.axiapay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kesofty.axiapay.R
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.telecom.*
import com.kesofty.axiapay.ui.bulk.*
import com.kesofty.axiapay.ui.components.*
import com.kesofty.axiapay.ui.telecom.TelecomSheet
import com.kesofty.axiapay.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Renders immutable app state; every action returns to the ViewModel as an intent. */
@Composable
fun AxiaApp(
    uiState: AxiaUiState,
    onIntent: (AxiaIntent) -> Unit,
    authShortcutLabel: String? = null,
    homeWallet: HomeWalletState = HomeWalletState.Demo,
    onRefreshWallet: () -> Unit = {},
    telecom: TelecomUiState? = null,
    onTelecomIntent: (TelecomIntent) -> Unit = {},
    onOpenAccount: () -> Unit = {},
) {
    if (uiState.isRestoring || uiState.restorationFailed) {
        SessionRestoration(uiState, onIntent)
        return
    }
    val state = uiState.session
    val navigation = uiState.navigation
    val onTab: (Int) -> Unit = { onIntent(AxiaIntent.SelectTab(it)) }
    val open: (String) -> Unit = { onIntent(AxiaIntent.OpenService(it)) }
    val onReceipt: (String) -> Unit = { onIntent(AxiaIntent.OpenReceipt(it)) }
    BackHandler(navigation.bulk.screen == null && navigation.sheet == null && navigation.tab != 0) {
        onTab(0)
    }
    when (navigation.bulk.screen) {
        null -> {
            Scaffold(
                containerColor = Canvas,
                bottomBar = { BottomNavigation(navigation.tab, onTab) },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (uiState.isProcessing) PaymentProgress()
                    if (state.offline || state.connectionMode != ConnectionMode.Automatic)
                        ConnectionBanner(state.connectionMode) { open("offline") }
                    Crossfade(
                        targetState = navigation.tab,
                        label = "Navigation",
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
                    ) { page ->
                        when (page) {
                            0 ->
                                HomeScreen(
                                    state,
                                    open,
                                    onTab,
                                    onReceipt,
                                    onIntent,
                                    homeWallet,
                                    onRefreshWallet,
                                    onOpenAccount,
                                )
                            1 ->
                                PaymentsScreen(
                                    navigation.serviceQuery,
                                    navigation.bulk.draft
                                        ?.takeUnless { draft ->
                                            state.batches.any { it.id == draft.id }
                                        }
                                        ?.recipients
                                        ?.size,
                                    { onIntent(AxiaIntent.SearchServices(it)) },
                                    open,
                                )
                            2 ->
                                ActivityScreen(
                                    state,
                                    navigation.activityFilter,
                                    { onIntent(AxiaIntent.FilterActivity(it)) },
                                    onReceipt,
                                    { onIntent(AxiaIntent.OpenSms(it)) },
                                    { onIntent(AxiaIntent.Bulk(BulkIntent.OpenBatch(it))) },
                                )
                            else -> PlansScreen(state, open, onIntent)
                        }
                    }
                }
            }
        }
        else -> BulkPaymentFlow(uiState, onIntent)
    }
    navigation.sheet?.let {
        AppSheet(
            it,
            state,
            navigation.draft,
            uiState.isProcessing,
            onIntent,
            onOpenAccount,
            authShortcutLabel,
            telecom,
            onTelecomIntent,
        )
    }
    (uiState.errorResource?.let { stringResource(it) } ?: uiState.error)?.let { message ->
        AlertDialog(
            onDismissRequest = { onIntent(AxiaIntent.DismissError) },
            title = { Text("Couldn't complete that") },
            text = { Text(message) },
            confirmButton = { TextButton({ onIntent(AxiaIntent.DismissError) }) { Text("OK") } },
        )
    }
}

/** Displays accessible in-flight feedback in the current palette without taking navigation away. */
@Composable
private fun PaymentProgress() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
        Text("Processing your request…", style = MaterialTheme.typography.bodyMedium, color = Pine)
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            color = Pine,
            trackColor = Mint,
        )
    }
}

/** Routes sheet state to existing forms and renders ephemeral progress without owning commands. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(
    sheet: Sheet,
    state: DemoSession,
    draft: PaymentFormState?,
    isProcessing: Boolean,
    onIntent: (AxiaIntent) -> Unit,
    onOpenAccount: () -> Unit,
    authShortcutLabel: String?,
    telecom: TelecomUiState?,
    onTelecomIntent: (TelecomIntent) -> Unit,
) {
    val close = { onIntent(AxiaIntent.CloseSheet) }
    val addMoney = { onIntent(AxiaIntent.OpenService("Add money")) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Line) },
    ) {
        Box(Modifier.fillMaxWidth().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                if (isProcessing) PaymentProgress()
                when (sheet) {
                    is Sheet.Receipt ->
                        state.payments
                            .find { it.id == sheet.paymentId }
                            ?.let {
                                ReceiptSheet(it, state, addMoney, close) {
                                    onIntent(AxiaIntent.CompleteWaiting(it.id))
                                }
                            }
                    Sheet.Notifications ->
                        NotificationsSheet(state, addMoney) { onIntent(AxiaIntent.SelectTab(3)) }
                    Sheet.Profile -> ProfileSheet(state, onIntent, onOpenAccount, authShortcutLabel)
                    Sheet.Offline -> OfflineSheet(state, draft, onIntent)
                    is Sheet.Sms ->
                        state.smsOutbox
                            .find { it.id == sheet.requestId }
                            ?.let { request ->
                                val status = state.payments.find { it.id == request.id }?.status
                                key(request.id, request.transport, status) {
                                    SmsPaymentSheet(request, state, onIntent)
                                }
                            }
                    is Sheet.Explore -> ExploreSheet(sheet.service)
                    is Sheet.Payment -> {
                        if (telecom != null && sheet.form.isTelecomPurchaseEntry) {
                            TelecomSheet(
                                telecom,
                                state.offline,
                                state.hidden,
                                onTelecomIntent,
                                onOpenAccount,
                            )
                        } else {
                            PaymentSheet(
                                sheet.form,
                                state,
                                { onIntent(AxiaIntent.EditPayment(it)) },
                                { onIntent(AxiaIntent.ConfirmPayment) },
                                { onIntent(AxiaIntent.SaveDraft) },
                                { onIntent(AxiaIntent.Bulk(BulkIntent.FromPayment)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(selectedTab: Int, onSelect: (Int) -> Unit) {
    Surface(color = Color.White, shadowElevation = 12.dp) {
        Row(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            listOf(
                    "Home" to Glyph.Home,
                    "Payments" to Glyph.Grid,
                    "Activity" to Glyph.Activity,
                    "Plans" to Glyph.Calendar,
                )
                .forEachIndexed { index, (label, icon) ->
                    val active = selectedTab == index
                    Column(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(role = Role.Tab) { onSelect(index) }
                            .semantics { selected = active }
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(width = 54.dp, height = 32.dp)
                                .background(
                                    if (active) Mint else Color.Transparent,
                                    RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            AxiaIcon(icon, Modifier.size(21.dp), if (active) Pine else Muted)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            label,
                            color = if (active) Pine else Muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
        }
    }
}

/** Render the current wallet source above the independently owned payment previews. */
@Composable
private fun HomeScreen(
    state: DemoSession,
    open: (String) -> Unit,
    onTab: (Int) -> Unit,
    onReceipt: (String) -> Unit,
    onIntent: (AxiaIntent) -> Unit,
    homeWallet: HomeWalletState,
    onRefreshWallet: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.testTag("home-dashboard"),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp)
                        .clip(CircleShape)
                        .background(Peach)
                        .clickable(role = Role.Button) { open("profile") }
                        .semantics { contentDescription = "Open profile" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("A", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Pine)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        "A little less to worry about.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    Text(
                        if (homeWallet == HomeWalletState.Demo) "Hello, Alex"
                        else stringResource(R.string.home_account_greeting),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Box {
                    RoundIconButton(Glyph.Bell, "Notifications", { open("notifications") })
                    if (!state.notificationsRead)
                        Box(
                            Modifier.padding(top = 10.dp, end = 12.dp)
                                .size(7.dp)
                                .align(Alignment.TopEnd)
                                .background(Color(0xFFE5A277).modeColor(), CircleShape)
                                .border(1.dp, Color.White, CircleShape),
                        )
                }
            }
        }
        item {
            when (homeWallet) {
                HomeWalletState.Demo -> WalletCard(state, open, onIntent)
                else ->
                    HomeWalletCard(
                        homeWallet,
                        state.hidden,
                        state.offline,
                        { onIntent(AxiaIntent.HideBalance(!state.hidden)) },
                        onRefreshWallet,
                        onOpenAccount,
                    )
            }
        }
        if (homeWallet != HomeWalletState.Demo) {
            item {
                Text(
                    stringResource(R.string.home_payment_preview_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        item {
            Column {
                SectionHeading("Your everyday, sorted", "See all") { onTab(1) }
                Spacer(Modifier.height(12.dp))
                ServiceGrid { if (it == "More") onTab(1) else open(it) }
            }
        }
        item {
            Column {
                SectionHeading("A little ahead", "All plans") { onTab(3) }
                Spacer(Modifier.height(8.dp))
                UpcomingCard(state, onTab)
            }
        }
        item {
            Column {
                SectionHeading("Recent activity", "View all") { onTab(2) }
                state.payments.take(3).forEach { payment ->
                    PaymentRow(payment, state.hidden) { onReceipt(payment.id) }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AxiaIcon(Glyph.Brand, Modifier.size(15.dp), Muted)
                Text("  axiapay", style = MaterialTheme.typography.titleSmall, color = Muted)
                Text(
                    "  /  Everyday, taken care of.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

/** Render the isolated demo ledger and its simulated funding/transfer actions. */
@Composable
private fun WalletCard(state: DemoSession, open: (String) -> Unit, onIntent: (AxiaIntent) -> Unit) {
    WalletCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.offline) "Cached balance" else "Available balance",
                color = Color(0xFFBDD7CF).modeColor(),
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(
                { onIntent(AxiaIntent.HideBalance(!state.hidden)) },
                Modifier.size(48.dp).padding(start = 2.dp),
            ) {
                AxiaIcon(
                    if (state.hidden) Glyph.EyeOff else Glyph.Eye,
                    Modifier.size(17.dp),
                    Color(0xFFBDD7CF).modeColor(),
                    if (state.hidden) "Show balance" else "Hide balance",
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Demo",
                color = Mint,
                style = MaterialTheme.typography.labelSmall,
                modifier =
                    Modifier.background(
                            Color.White.copy(alpha = .08f),
                            RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        MoneyAmount(
            if (state.hidden) "₦ ••••••" else money(state.balance, true),
            color = Color.White,
            modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(Mint, CircleShape))
            Text(
                if (state.offline) "  Last seen ${state.cachedTime} • Not live"
                else "  Your everyday possibilities.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFBDD7CF).modeColor(),
            )
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                { open("Add money") },
                Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors =
                    ButtonDefaults.buttonColors(containerColor = Mint, contentColor = DeepPine),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            ) {
                AxiaIcon(Glyph.Plus, Modifier.size(18.dp), DeepPine)
                Spacer(Modifier.width(7.dp))
                Text("Add money", style = MaterialTheme.typography.labelMedium)
            }
            Button(
                { open("Send money") },
                Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = .10f),
                        contentColor = Color.White,
                    ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            ) {
                AxiaIcon(Glyph.ArrowUp, Modifier.size(17.dp), Color.White)
                Spacer(Modifier.width(7.dp))
                Text(
                    "Send money",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ServiceGrid(onSelect: (String) -> Unit) {
    val quickServices = services.take(7) + Service("More", Glyph.More, "")
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth.value / (80f * fontScale)).toInt().coerceIn(2, 4)
        Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
            quickServices.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { service ->
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(role = Role.Button) { onSelect(service.name) }
                                .padding(vertical = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            IconTile(
                                service.icon,
                                Modifier.size(50.dp),
                                if (service.name == "More") Color(0xFFEAF0ED).modeColor()
                                else glyphTint(service.icon),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (service.name == "Convert airtime") "Airtime to cash"
                                else service.name,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun UpcomingCard(state: DemoSession, onTab: (Int) -> Unit) {
    val next = state.plans.filter { it.enabled }.minByOrNull { it.date }
    val amountLabel = next?.let { if (state.hidden) "••••" else money(it.amount) }
    val fundingLabel = next?.let {
        when {
            state.offline -> "Saved plan"
            state.balance >= it.amount -> "Wallet is ready"
            else -> "Top-up needed"
        }
    }
    val actionLabel = next?.let { "$amountLabel  •  $fundingLabel" } ?: "Create a plan"
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFFE9F1EA).modeColor())
            .clickable(role = Role.Button) { onTab(3) }
            .padding(17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp)
                .background(Color.White.copy(alpha = .8f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AxiaIcon(next?.icon ?: Glyph.Calendar, Modifier.size(27.dp), Pine)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                if (next == null) "Make room for peace of mind" else "One less thing to remember.",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (next == null) "Schedule your next payment"
                else "${next.detail} renews ${relativeDate(next.date)}",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Pine,
            )
        }
        AxiaIcon(Glyph.Chevron, Modifier.size(17.dp), Pine)
    }
}

private fun relativeDate(day: LocalDate): String {
    return if (day == LocalDate.now().plusDays(1)) "tomorrow"
    else "on ${day.format(DateTimeFormatter.ofPattern("d MMM"))}"
}

@Composable
private fun PageHeader(title: String, subtitle: String, icon: Glyph = Glyph.Brand) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Box(
            Modifier.size(48.dp).background(Mint, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AxiaIcon(icon, color = Pine)
        }
    }
}

/** Renders the searchable service catalog and forwards query edits and service selection. */
@Composable
private fun PaymentsScreen(
    query: String,
    bulkDraftCount: Int?,
    onQuery: (String) -> Unit,
    open: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageHeader("Life, uninterrupted.", "All your everyday payments, in one place.") }
        if (query.isBlank() || "bulk payments".contains(query, true))
            item { BulkEntryCard(bulkDraftCount) { open("bulk") } }
        item {
            OutlinedTextField(
                query,
                onQuery,
                Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Find a service", style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = { AxiaIcon(Glyph.Search, Modifier.size(20.dp), Muted) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = ControlOutline,
                        unfocusedContainerColor = Color.White,
                    ),
            )
        }
        val filtered = services.filter {
            it.name.contains(query, true) || it.hint.contains(query, true)
        }
        items(filtered, key = { it.name }) { service ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .clickable(role = Role.Button) { open(service.name) }
                    .padding(15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(service.icon)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(service.name, style = MaterialTheme.typography.titleSmall)
                    Text(service.hint, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                AxiaIcon(Glyph.Chevron, Modifier.size(17.dp), Muted)
            }
        }
        if (filtered.isEmpty())
            item { EmptyState("No services found", "Try airtime, data, electricity or TV.") }
        if (query.isBlank()) {
            item { SectionHeading("A little more power") }
            item {
                UtilityCard(
                    "No internet? Stay connected.",
                    "Try airtime and data payments by SMS.",
                    Glyph.Offline,
                    Peach,
                ) {
                    open("offline")
                }
            }
        }
    }
}

@Composable
private fun UtilityCard(
    title: String,
    subtitle: String,
    icon: Glyph,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(color)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AxiaIcon(icon, Modifier.size(27.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Ink.copy(alpha = .7f),
            )
        }
        AxiaIcon(Glyph.Chevron, Modifier.size(17.dp))
    }
}

/** Displays demo activity and visibly outlined filters; all changes remain callback-driven. */
@Composable
private fun ActivityScreen(
    state: DemoSession,
    filter: String,
    onFilter: (String) -> Unit,
    onReceipt: (String) -> Unit,
    onSms: (String) -> Unit,
    onBatch: (String) -> Unit,
) {
    val batchPaymentIds =
        state.batches
            .flatMap { batch -> batch.recipients.map { "${batch.id}:${it.details.id}" } }
            .toSet()
    val individualPayments = state.payments.filterNot { it.id in batchPaymentIds }
    val visibleBatches =
        when (filter) {
            "Money in" -> emptyList()
            "Pending" -> state.batches.filter { it.hasPending }
            else -> state.batches
        }
    val filtered = individualPayments.filter {
        when (filter) {
            "Batches" -> false
            "Money in" -> it.amount > 0
            "Money out" -> it.amount < 0
            "Pending" -> it.status == PaymentStatus.Waiting || it.status == PaymentStatus.Scheduled
            else -> true
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageHeader("Your money, in view.", "Every payment has a story.", Glyph.Activity) }
        item {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Pine)
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Money in", color = Mint, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (state.hidden) "••••"
                        else
                            money(
                                state.payments
                                    .filter {
                                        it.amount > 0 && it.status == PaymentStatus.Successful
                                    }
                                    .sumOf { it.amount },
                            ),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Money out", color = Mint, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (state.hidden) "••••"
                        else
                            money(
                                state.batches.sumOf { it.paidMinorUnits } -
                                    individualPayments
                                        .filter {
                                            it.amount < 0 && it.status == PaymentStatus.Successful
                                        }
                                        .sumOf { it.amount },
                            ),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                }
            }
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("All", "Batches", "Money in", "Money out", "Pending").forEach { name ->
                    SelectionChip(
                        if (name == "Batches") stringResource(R.string.bulk_batches) else name,
                        filter == name,
                        { onFilter(name) },
                    )
                }
            }
        }
        if (filtered.isEmpty() && visibleBatches.isEmpty())
            item { EmptyState("Nothing here just yet", "Your matching payments will appear here.") }
        if (filter !in listOf("Money in", "Batches")) {
            items(
                state.smsOutbox.filter { sms -> state.payments.none { it.id == sms.id } },
                key = { "sms-${it.id}" },
            ) { sms ->
                SmsOutboxRow(sms, state.hidden) { onSms(sms.id) }
            }
        }
        items(filtered, key = { it.id }) { payment ->
            PaymentRow(payment, state.hidden) { onReceipt(payment.id) }
        }
        if (visibleBatches.isNotEmpty())
            item { SectionHeading(stringResource(R.string.bulk_history_heading)) }
        items(visibleBatches, key = { "batch-${it.id}" }) { batch ->
            BulkHistoryCard(batch, state.hidden, state.offline) { onBatch(batch.id) }
        }
        item {
            Text(
                "Sample activity. No real money has moved.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun PlansScreen(
    state: DemoSession,
    open: (String) -> Unit,
    onIntent: (AxiaIntent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.testTag("plans-list"),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            PageHeader(
                "Meet your future self.",
                "They'll thank you for planning ahead.",
                Glyph.Calendar,
            )
        }
        item {
            Column(
                Modifier.fillMaxWidth().background(Peach, RoundedCornerShape(24.dp)).padding(22.dp),
            ) {
                Text("Already thought of.", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.plans.count { it.enabled }} active plans • ${if (state.hidden) "••••" else money(state.plans.filter { it.enabled }.sumOf { it.amount })} in next payments",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.copy(alpha = .7f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Schedule a payment") { open("Schedule payment") }
            }
        }
        item { SectionHeading("Your plans") }
        items(state.plans, key = { it.id }) { plan ->
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(22.dp))
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(plan.icon)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(plan.title, style = MaterialTheme.typography.titleSmall)
                        Text(plan.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        plan.enabled,
                        { onIntent(AxiaIntent.SetPlan(plan.id, it)) },
                        modifier =
                            Modifier.semantics { contentDescription = "Enable ${plan.title}" },
                    )
                }
                Row(
                    Modifier.padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AxiaIcon(
                        if (plan.recurrence == Recurrence.Once) Glyph.Calendar else Glyph.Convert,
                        Modifier.size(16.dp),
                        Pine,
                    )
                    Text(
                        "${plan.recurrence.label} • ${plan.recurrence.description(plan.date)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Line)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(
                            if (state.hidden) "••••" else money(plan.amount),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (plan.recurrence != Recurrence.Once)
                            Text(
                                "per payment",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                    }
                    Text(
                        if (plan.enabled)
                            "Next ${plan.date.format(DateTimeFormatter.ofPattern("d MMM"))}"
                        else "Paused",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (plan.enabled) Pine else Muted,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconTile(Glyph.Bell, tint = Lavender)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("A friendly heads-up", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Remind me before a payment is due",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Switch(
                    state.reminderEnabled,
                    { onIntent(AxiaIntent.SetReminders(it)) },
                    modifier = Modifier.semantics { contentDescription = "Payment reminders" },
                )
            }
        }
        item {
            Text(
                "Preview only. Plans and reminders are saved for this demo session; automatic payments and push notifications aren't connected yet.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}
