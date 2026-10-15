package com.kesofty.axiapay.presentation

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kesofty.axiapay.R
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** MVI entry point. Views emit intents; only this owner publishes render state. */
@HiltViewModel
class AxiaViewModel
@Inject
constructor(
    private val observe: ObserveSession,
    private val submit: SubmitPayment,
    private val completeWaiting: CompletePayment,
    private val queueBulk: QueueBulkBatch,
    private val retryBulk: RetryBulkBatch,
    private val runBulk: RunBulkBatch,
    private val bulkForms: BulkFormPresenter,
    private val prepareImport: PrepareRecipientImport,
    private val exportSample: ExportRecipientSample,
    private val setPlan: UpdatePlan,
    private val setHidden: SetBalanceVisibility,
    private val setReminders: SetPaymentReminders,
    private val markRead: MarkNotificationsRead,
    private val setConnection: UpdateConnectionState,
    private val setConnectionMode: SetConnectionMode,
    private val internet: ObserveInternetConnection,
    private val prepareSms: PrepareSmsRequest,
    private val sendSms: SendSmsRequest,
    private val reconcileSms: ReconcileSmsRequest,
    private val forms: PaymentFormPresenter,
    private val navigationStore: NavigationStore,
    private val newRequestId: () -> String,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle =
        androidx.lifecycle.SavedStateHandle(),
) : ViewModel() {
    private val session = observe()
    private val mutableState =
        MutableStateFlow(AxiaUiState(session.value, NavigationState(), isRestoring = true))
    val uiState = mutableState.asStateFlow()
    // Navigation revisions prevent a result from taking over a screen opened after submission.
    private var navigationRevision = 0L
    private var importJob: Job? = null
    private var sampleDownloadJob: Job? = null

    private val checkpointRequests = Channel<Unit>(Channel.CONFLATED)
    private var restoreJob: Job? = null
    private var connectionJob: Job? = null

    init {
        viewModelScope.launch {
            for (request in checkpointRequests) {
                try {
                    navigationStore.persist()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(errorResource = R.string.bulk_draft_save_failed) }
                }
            }
        }
        viewModelScope.launch {
            session.collect { value -> mutableState.update { it.copy(session = value) } }
        }
        restoreOwner()
    }

    /**
     * Restores large checkpoints on their worker dispatchers; failure blocks all spending paths.
     */
    private fun restoreOwner() {
        if (restoreJob?.isActive == true) return
        mutableState.update { it.copy(isRestoring = true, restorationFailed = false) }
        restoreJob =
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    when (val result = observe.restore()) {
                        is Outcome.Failure ->
                            throw IllegalStateException(
                                "Cannot restore session",
                                (result.error as? DemoError.Storage)?.cause,
                            )
                        is Outcome.Success -> Unit
                    }
                    val restored = restoreNavigation()
                    navigationStore.save(restored)
                    navigationStore.persist()
                    savedStateHandle["checkpoint.session.v1.ready"] = true
                    savedStateHandle["checkpoint.navigation.v1.ready"] = true
                    savedStateHandle.remove<String>("demo.session.v1")
                    savedStateHandle.remove<String>("presentation.navigation.v1")
                    handle(setConnection(!internet.currentIsOnline()))
                    mutableState.update {
                        it.copy(
                            session = session.value,
                            navigation = restored,
                            isRestoring = false,
                            restorationFailed = false,
                        )
                    }
                    observeConnectivity()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(isRestoring = false, restorationFailed = true) }
                }
            }
    }

    /** Connectivity updates metadata only; checkpoint restoration never resumes pending batches. */
    private fun observeConnectivity() {
        if (connectionJob != null) return
        connectionJob = viewModelScope.launch {
            internet()
                .catch { cause ->
                    handle(setConnection(true))
                    report(DemoError.ConnectionMonitoring(cause))
                }
                .collect { online -> handle(setConnection(!online)) }
        }
    }

    /** Performs a suspending preference write independently of payment submission guards. */
    private fun preference(action: suspend () -> Outcome<Unit>) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { handle(action()) }
    }

    /** Routes user intents to use cases or navigation; rendering cannot replay an action. */
    @MainThread
    fun accept(intent: AxiaIntent) {
        if (intent == AxiaIntent.RetryRestore) {
            restoreOwner()
            return
        }
        if (uiState.value.isRestoring || uiState.value.restorationFailed) return
        when (intent) {
            is AxiaIntent.SelectTab ->
                navigate(
                    navigation.copy(
                        tab = intent.tab.coerceIn(0, 3),
                        sheet = null,
                        serviceQuery = "",
                        activityFilter = "All",
                        bulk = navigation.bulk.copy(screen = null),
                    ),
                )
            is AxiaIntent.OpenService -> open(intent.route)
            is AxiaIntent.OpenReceipt -> openReceipt(intent.id)
            AxiaIntent.CloseSheet -> closeSheet()
            is AxiaIntent.SearchServices -> navigate(navigation.copy(serviceQuery = intent.query))
            is AxiaIntent.FilterActivity ->
                navigate(navigation.copy(activityFilter = intent.filter))
            is AxiaIntent.HideBalance -> preference { setHidden(intent.hidden) }
            is AxiaIntent.SetConnectionMode -> preference { setConnectionMode(intent.mode) }
            is AxiaIntent.SetReminders -> preference { setReminders(intent.enabled) }
            is AxiaIntent.SetPlan -> executeAction({ setPlan(intent.id, intent.enabled) }, ::handle)
            is AxiaIntent.CompleteWaiting ->
                executeAction({ completeWaiting(intent.id) }, ::showReceipt)
            is AxiaIntent.Bulk -> handleBulk(intent.intent)
            is AxiaIntent.EditPayment -> editPayment(intent.intent)
            AxiaIntent.ConfirmPayment -> confirmPayment()
            is AxiaIntent.OpenSms -> navigate(navigation.copy(sheet = Sheet.Sms(intent.id)))
            is AxiaIntent.SendSms ->
                executeAction({ sendSms(intent.id, intent.simulateFailure) }, ::showSms)
            is AxiaIntent.ReconcileSms ->
                executeAction({ reconcileSms(intent.id) }, ::showSmsOutcome)
            AxiaIntent.SaveDraft -> saveDraft()
            AxiaIntent.ResumeDraft -> resumeDraft()
            AxiaIntent.RetryRestore -> Unit
            AxiaIntent.DismissError ->
                mutableState.update { it.copy(error = null, errorResource = null) }
        }
    }

    /** Keeps bulk editing in its reducer and routes only explicit commands to use cases. */
    private fun handleBulk(intent: BulkIntent) {
        val state = navigation.bulk
        when (intent) {
            is BulkIntent.SampleDownload -> handleSampleDownload(intent.intent)
            BulkIntent.OpenImport -> {
                if (state.screen != BulkScreen.Recipients || state.draft == null) return
                navigate(
                    navigation.copy(
                        bulk =
                            state.copy(
                                screen = BulkScreen.Import,
                                importState =
                                    BulkImportState(
                                        newRequestId(),
                                        sampleFormats = exportSample.availableFormats,
                                    ),
                            ),
                    ),
                )
            }
            is BulkIntent.ImportFile -> readImport(intent)
            is BulkIntent.SelectImportRow -> selectImportRow(intent.id)
            is BulkIntent.SearchImport -> updateImport { it.copy(query = intent.query.take(80)) }
            is BulkIntent.FilterImport -> updateImport { it.copy(filter = intent.filter) }
            is BulkIntent.SelectImportRows ->
                updateImport { current ->
                    val ids =
                        if (intent.select)
                            current.preview
                                ?.rows
                                .orEmpty()
                                .filter { it.valid }
                                .take(current.preview?.availableSlots ?: 0)
                                .map { it.input.id }
                                .toSet()
                        else emptySet()
                    current.copy(selectedIds = ids)
                }
            BulkIntent.AddImportedRecipients -> appendImport()
            BulkIntent.ImportPickerUnavailable -> {
                val current = state.importState ?: return
                navigate(
                    navigation.copy(
                        bulk =
                            state.copy(
                                importState = current.copy(failure = ImportFileIssue.Unreadable),
                            ),
                    ),
                )
            }
            BulkIntent.Open ->
                navigate(
                    navigation.copy(
                        sheet = null,
                        bulk = bulkForms.open(state, session.value.batches.map { it.id }.toSet()),
                    ),
                )
            is BulkIntent.ChooseService ->
                navigate(
                    navigation.copy(
                        bulk =
                            bulkForms.chooseService(
                                bulkForms.restore(
                                    state,
                                    session.value.batches.map { it.id }.toSet(),
                                ),
                                intent.service,
                            ),
                    ),
                )
            BulkIntent.FromPayment -> expandPaymentRecipients()
            BulkIntent.History ->
                navigate(
                    navigation.copy(
                        tab = 2,
                        sheet = null,
                        activityFilter = "Batches",
                        bulk = state.copy(screen = null),
                    ),
                )
            BulkIntent.Submit -> submitBulkDraft()
            BulkIntent.ConfirmRetry -> {
                val id = state.batchId ?: return
                if (state.screen != BulkScreen.RetryReview) return
                executeBulk(id, BulkScreen.RetryReview) { retryBulk(id) }
            }
            BulkIntent.Resume -> {
                val id = state.batchId ?: return
                val batch = session.value.batches.find { it.id == id } ?: return
                if (!batch.hasPending) return
                executeBulk(id, BulkScreen.Progress) { Outcome.Success(batch) }
            }
            else -> navigate(navigation.copy(bulk = bulkForms.reduce(state, intent)))
        }
    }

    /** Coordinates sample-file effects separately from import selection and payment execution. */
    private fun handleSampleDownload(intent: SampleDownloadIntent) {
        val current = navigation.bulk.importState ?: return
        if (navigation.bulk.screen != BulkScreen.Import) return
        val request = current.sampleDownload
        when (intent) {
            is SampleDownloadIntent.Request -> {
                val service = navigation.bulk.draft?.service ?: return
                if (
                    current.loading ||
                        request?.busy == true ||
                        intent.format !in current.sampleFormats
                )
                    return
                updateSampleDownload(
                    SampleDownloadState(
                        newRequestId(),
                        RecipientSample(service, intent.format, intent.recipientCount),
                    ),
                )
            }
            is SampleDownloadIntent.PickerOpened -> {
                if (
                    request?.id != intent.requestId ||
                        request.phase != SampleDownloadPhase.LaunchPicker
                )
                    return
                updateSampleDownload(request.copy(phase = SampleDownloadPhase.ChoosingLocation))
            }
            is SampleDownloadIntent.PickerUnavailable -> {
                if (
                    request?.id != intent.requestId ||
                        request.phase != SampleDownloadPhase.ChoosingLocation
                )
                    return
                updateSampleDownload(request.copy(phase = SampleDownloadPhase.Failed))
            }
            is SampleDownloadIntent.Destination -> {
                if (
                    request?.id != intent.requestId ||
                        request.phase != SampleDownloadPhase.ChoosingLocation
                )
                    return
                if (intent.reference == null) {
                    updateSampleDownload(null)
                    return
                }
                saveSample(current.id, request, intent.reference)
            }
        }
    }

    /** Performs one explicit write; late completion cannot replace a newer session or draft. */
    private fun saveSample(sessionId: String, request: SampleDownloadState, reference: String) {
        updateSampleDownload(request.copy(phase = SampleDownloadPhase.Saving))
        sampleDownloadJob = viewModelScope.launch {
            try {
                val result = exportSample(request.sample, reference)
                finishSampleDownload(sessionId, request, result)
            } catch (cancelled: CancellationException) {
                finishSampleDownload(sessionId, request, SampleExportResult.Failed(cancelled))
                throw cancelled
            } catch (failure: Exception) {
                finishSampleDownload(sessionId, request, SampleExportResult.Failed(failure))
            }
        }
    }

    /** Preserves concurrent row selection and consumes results only for the originating write. */
    private fun finishSampleDownload(
        sessionId: String,
        request: SampleDownloadState,
        result: SampleExportResult,
    ) {
        val current = navigation.bulk.importState ?: return
        if (current.id != sessionId || current.sampleDownload?.id != request.id) return
        val updated =
            when (result) {
                SampleExportResult.Saved -> request.copy(phase = SampleDownloadPhase.Saved)
                is SampleExportResult.Failed ->
                    request.copy(phase = SampleDownloadPhase.Failed, failure = result)
            }
        updateSampleDownload(updated)
    }

    /** Publishes only the ephemeral export field, retaining all recipient and import state. */
    private fun updateSampleDownload(download: SampleDownloadState?) {
        val flow = navigation.bulk
        val current = flow.importState ?: return
        navigate(
            navigation.copy(
                bulk = flow.copy(importState = current.copy(sampleDownload = download)),
            ),
        )
    }

    /** Reads one local file at a time; late or cancelled reads cannot replace another screen. */
    private fun readImport(intent: BulkIntent.ImportFile) {
        val flow = navigation.bulk
        val current = flow.importState ?: return
        val draft = flow.draft ?: return
        if (
            flow.screen != BulkScreen.Import ||
                current.id != intent.sessionId ||
                current.loading ||
                current.sampleDownload?.busy == true
        )
            return
        navigate(
            navigation.copy(
                bulk =
                    flow.copy(
                        importState =
                            current.copy(
                                loading = true,
                                preview = null,
                                selectedIds = emptySet(),
                                failure = null,
                            ),
                    ),
            ),
        )
        val revision = navigationRevision
        importJob = viewModelScope.launch {
            try {
                val result = prepareImport(intent.reference, draft, flow.editor)
                if (navigationRevision != revision) return@launch
                val updated =
                    when (result) {
                        is RecipientImportResult.Ready ->
                            current.copy(
                                preview = result.preview,
                                selectedIds =
                                    result.preview.rows
                                        .filter { it.valid }
                                        .map { it.input.id }
                                        .toSet(),
                            )
                        is RecipientImportResult.Failed ->
                            current.copy(failure = result.failure.issue)
                    }
                navigate(navigation.copy(bulk = navigation.bulk.copy(importState = updated)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (navigationRevision == revision)
                    navigate(
                        navigation.copy(
                            bulk =
                                navigation.bulk.copy(
                                    importState =
                                        current.copy(failure = ImportFileIssue.Unreadable),
                                ),
                        ),
                    )
            } finally {
                val active = navigation.bulk.importState
                if (active?.id == current.id && active.loading)
                    navigate(
                        navigation.copy(
                            bulk = navigation.bulk.copy(importState = active.copy(loading = false)),
                        ),
                    )
            }
        }
    }

    /** Keeps import display/selection changes in the same owner without replaying file reads. */
    private fun updateImport(change: (BulkImportState) -> BulkImportState) {
        val current = navigation.bulk.importState ?: return
        if (current.loading) return
        navigate(navigation.copy(bulk = navigation.bulk.copy(importState = change(current))))
    }

    /**
     * Changes selection only for rows validated by the use case; no file IO or payment is replayed.
     */
    private fun selectImportRow(id: String) {
        val flow = navigation.bulk
        val current = flow.importState ?: return
        if (
            current.loading ||
                current.preview?.rows?.none { it.input.id == id && it.valid } != false
        )
            return
        val selected =
            if (id in current.selectedIds) current.selectedIds - id else current.selectedIds + id
        navigate(
            navigation.copy(bulk = flow.copy(importState = current.copy(selectedIds = selected))),
        )
    }

    /**
     * Revalidates selection at the application boundary before atomically extending the saved
     * draft.
     */
    private fun appendImport() {
        val flow = navigation.bulk
        val current = flow.importState ?: return
        if (flow.screen != BulkScreen.Import || !current.canAdd) return
        val draft = flow.draft ?: return
        val preview = current.preview ?: return
        val updated =
            prepareImport.append(preview, current.selectedIds, draft, flow.editor) ?: return
        navigate(
            navigation.copy(
                bulk =
                    bulkForms.refresh(
                        flow.copy(
                            screen = BulkScreen.Recipients,
                            draft = updated,
                            editor =
                                flow.editor?.takeUnless { editor ->
                                    editor.name.isBlank() &&
                                        editor.recipientReference.isBlank() &&
                                        draft.recipients.none { it.id == editor.id }
                                },
                            recipientQuery = "",
                            importState = null,
                        ),
                    ),
            ),
        )
    }

    /**
     * Converts only a fresh, unscheduled form and removes its single-payment alias after transfer.
     */
    private fun expandPaymentRecipients() {
        val form = (navigation.sheet as? Sheet.Payment)?.form ?: return
        if (uiState.value.isProcessing || form.input.scheduled || form.reviewing) return
        if (!form.supportsMoreRecipients || !form.validation.valid) return
        if (session.value.payments.any { it.id == form.requestId }) return
        val bulk =
            bulkForms.fromPayment(
                bulkForms.restore(navigation.bulk, session.value.batches.map { it.id }.toSet()),
                form,
            )
        val transferred =
            bulk.editor?.id == form.requestId ||
                bulk.draft?.recipients?.any { it.id == form.requestId } == true
        // A blocked older inline edit keeps the individual draft recoverable through Resume draft.
        val draft =
            if (transferred) navigation.draft?.takeUnless { it.requestId == form.requestId }
            else form
        navigate(navigation.copy(sheet = null, draft = draft, bulk = bulk))
    }

    /** Captures reviewed input before navigation; a button press is the sole submission trigger. */
    private fun submitBulkDraft() {
        val state = navigation.bulk
        val draft = state.draft ?: return
        if (state.screen != BulkScreen.Review) return
        executeBulk(draft.id, BulkScreen.Review) { queueBulk(draft) }
    }

    /** Shows live repository progress while the caller-owned demo job advances reserved rows. */
    private fun executeBulk(
        id: String,
        fallback: BulkScreen,
        reserve: suspend () -> Outcome<BulkBatch>,
    ) {
        if (mutableState.value.isProcessing) return
        if (session.value.offline) {
            report(DemoError.OnlineRequired)
            return
        }
        navigate(
            navigation.copy(
                bulk =
                    navigation.bulk.copy(
                        screen = BulkScreen.Progress,
                        batchId = id,
                        filter = BulkRecipientFilter.All,
                    ),
            ),
        )
        executeAction(
            {
                when (val result = reserve()) {
                    is Outcome.Failure -> result
                    is Outcome.Success -> runBulk(id)
                }
            },
        ) { result ->
            when (result) {
                is Outcome.Success -> {
                    val draft = navigation.bulk.draft?.takeUnless { it.id == id }
                    navigate(navigation.copy(bulk = navigation.bulk.copy(draft = draft)))
                }
                is Outcome.Failure -> {
                    if (session.value.batches.none { it.id == id })
                        navigate(navigation.copy(bulk = navigation.bulk.copy(screen = fallback)))
                    report(result.error)
                }
            }
        }
    }

    /**
     * Runs one explicit action per owner. Busy state is ephemeral, never restored or replayed. Only
     * the originating navigation revision consumes its result; repository state still updates after
     * navigation. Cancellation says nothing about a server outcome and is never a failure.
     */
    private fun <T> executeAction(
        action: suspend () -> Outcome<T>,
        consume: (Outcome<T>) -> Unit,
    ) {
        if (mutableState.value.isProcessing) return
        val originRevision = navigationRevision
        mutableState.update { it.copy(isProcessing = true, error = null, errorResource = null) }
        viewModelScope.launch {
            try {
                val result = action()
                if (originRevision == navigationRevision) consume(result)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                if (originRevision == navigationRevision)
                    report(DemoError.OperationUnconfirmed(cause))
            } finally {
                mutableState.update { it.copy(isProcessing = false, session = session.value) }
            }
        }
    }

    private val navigation
        get() = mutableState.value.navigation

    /** Opens a destination; notification read state changes only through its use case. */
    private fun open(route: String) {
        if (route == "bulk") {
            handleBulk(BulkIntent.Open)
            return
        }
        val sheet =
            when (route) {
                "notifications" -> Sheet.Notifications
                "profile" -> Sheet.Profile
                "offline" -> Sheet.Offline
                "Insurance",
                "Education",
                "Convert airtime",
                "Merchants" -> Sheet.Explore(route)
                else -> Sheet.Payment(formFor(route))
            }
        navigate(navigation.copy(sheet = sheet))
        if (sheet == Sheet.Notifications) preference { markRead() }
    }

    /** Routes SMS identities to their transport status and other identities to receipts. */
    private fun openReceipt(id: String) {
        val sheet =
            when {
                session.value.smsOutbox.any { it.id == id } -> Sheet.Sms(id)
                else -> Sheet.Receipt(id)
            }
        navigate(navigation.copy(sheet = sheet))
    }

    /** Reduces one form intent without performing a payment. */
    private fun editPayment(intent: PaymentIntent) {
        val sheet = navigation.sheet as? Sheet.Payment ?: return
        navigate(navigation.copy(sheet = Sheet.Payment(forms.reduce(sheet.form, intent))))
    }

    /** Captures reviewed terms and routes an explicit confirmation to online or SMS work. */
    private fun confirmPayment() {
        val sheet = navigation.sheet as? Sheet.Payment ?: return
        if (!sheet.form.reviewing) return
        if (session.value.offline) {
            executeAction(
                { prepareSms(sheet.form.input, sheet.form.requestId, sheet.form.smsSim) },
                ::showSms,
            )
            return
        }
        executeAction({ submit(sheet.form.input, sheet.form.requestId) }, ::showReceipt)
    }

    /** Resumes a matching draft or assigns a fresh identity to a new form. */
    private fun formFor(route: String): PaymentFormState {
        val draft = navigation.draft?.takeIf { it.route == route }
        return draft?.let(forms::refresh) ?: forms.create(route, newRequestId())
    }

    /** Closes the sheet while retaining any unsubmitted form. */
    private fun closeSheet() {
        val form = (navigation.sheet as? Sheet.Payment)?.form
        navigate(navigation.copy(sheet = null, draft = form ?: navigation.draft))
    }

    /** Saves the current form and returns to the offline panel without submitting it. */
    private fun saveDraft() {
        val form = (navigation.sheet as? Sheet.Payment)?.form ?: return
        navigate(navigation.copy(sheet = Sheet.Offline, draft = form))
    }

    /** Revalidates the retained form without executing a request. */
    private fun resumeDraft() {
        val form = navigation.draft ?: return
        navigate(navigation.copy(sheet = Sheet.Payment(forms.refresh(form))))
    }

    /** Consumes a prepared or sent SMS result without treating transport as payment success. */
    private fun showSms(result: Outcome<SmsRequest>) {
        when (result) {
            is Outcome.Success ->
                navigate(
                    navigation.copy(
                        sheet = Sheet.Sms(result.value.id),
                        draft = navigation.draft?.takeUnless { it.requestId == result.value.id },
                    ),
                )
            is Outcome.Failure -> report(result.error)
        }
    }

    /** Keeps reconciliation results on the SMS status sheet. */
    private fun showSmsOutcome(result: Outcome<Payment>) {
        when (result) {
            is Outcome.Success -> navigate(navigation.copy(sheet = Sheet.Sms(result.value.id)))
            is Outcome.Failure -> report(result.error)
        }
    }

    /** Shows a recorded result and removes only its matching draft. */
    private fun showReceipt(result: Outcome<Payment>) {
        when (result) {
            is Outcome.Success ->
                navigate(
                    navigation.copy(
                        sheet = Sheet.Receipt(result.value.id),
                        draft = navigation.draft?.takeUnless { it.requestId == result.value.id },
                    ),
                )
            is Outcome.Failure -> report(result.error)
        }
    }

    /** Publishes a committed preference change or its explicit error. */
    private fun handle(result: Outcome<Unit>) {
        when (result) {
            is Outcome.Success -> mutableState.update { it.copy(session = session.value) }
            is Outcome.Failure -> report(result.error)
        }
    }

    /** Saves presentation state and advances the result-consumption revision. */
    private fun navigate(value: NavigationState) {
        val next =
            if (value.bulk.screen == BulkScreen.Import) value
            else value.copy(bulk = value.bulk.copy(importState = null))
        val cancelImport = next.bulk.importState?.id != navigation.bulk.importState?.id
        navigationStore.save(next)
        checkpointRequests.trySend(Unit)
        navigationRevision += 1
        mutableState.update {
            it.copy(navigation = next, session = session.value, error = null, errorResource = null)
        }
        if (cancelImport) importJob?.cancel()
        if (cancelImport) sampleDownloadJob?.cancel()
    }

    /** Restores fields and revalidates a form without replaying commands. */
    private suspend fun restoreNavigation(): NavigationState {
        val saved = navigationStore.load() ?: NavigationState()
        val restored =
            saved.copy(
                bulk = bulkForms.restore(saved.bulk, session.value.batches.map { it.id }.toSet()),
            )
        val sheet = restored.sheet as? Sheet.Payment ?: return restored
        return restored.copy(sheet = Sheet.Payment(forms.refresh(sheet.form)))
    }

    /** Maps explicit errors to safe display text without declaring uncertain payments failed. */
    private fun report(error: DemoError) {
        val message =
            when (error) {
                DemoError.BulkPreviewLimit -> null
                DemoError.OnlineRequired ->
                    "Reconnect to use this action. Your saved details and plans are still here."
                DemoError.SmsUnsupported ->
                    "This SMS demo supports one-time airtime and data payments. Save other payments as drafts."
                DemoError.SmsAlreadySubmitted ->
                    "This request is in your SMS outbox. Check its status there instead of paying again."
                DemoError.SmsNotSent ->
                    "Send this SMS request before checking for a payment result."
                DemoError.RequestExpired ->
                    "This unsent SMS review has expired. Start a new payment and review the details again."
                DemoError.RetryLimit ->
                    "Three demo attempts have failed. Nothing was sent. Start a new payment when mobile service is available."
                DemoError.InvalidAmount -> "Enter a valid amount between ₦1 and ₦1,000,000."
                DemoError.InvalidRecipient -> "Check the recipient details and try again."
                DemoError.InvalidDate -> "Choose a future payment date."
                DemoError.InvalidProvider -> "Choose one of the sample providers."
                DemoError.InvalidPlan -> "Choose a sample data plan again."
                DemoError.NotFound -> "This demo payment or plan could not be found."
                DemoError.InsufficientFunds -> "Add money before completing this payment."
                DemoError.RequestConflict ->
                    "This request was already used for different payment details. Open a new payment."
                is DemoError.ConnectionMonitoring ->
                    "Internet monitoring stopped. Your drafts and pending payments are safe. Reopen the app to check your connection."
                is DemoError.OperationUnconfirmed ->
                    "The result could not be confirmed. Check Activity or the SMS outbox before trying again."
                is DemoError.Storage ->
                    "The demo change could not be saved. Your balance has not changed. Try again."
            }
        mutableState.update {
            it.copy(
                session = session.value,
                error = message,
                errorResource =
                    if (error == DemoError.BulkPreviewLimit) R.string.bulk_history_limit else null,
            )
        }
    }
}
