package com.kesofty.axiapay.presentation

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.AmountPolicy
import com.kesofty.axiapay.domain.BulkLimits
import com.kesofty.axiapay.domain.BulkService

/**
 * Owns quick-list editing and draft routing; application validation owns reviewed payment terms.
 */
class BulkFormPresenter(private val prepare: PrepareBulkBatch, private val newId: () -> String) {
    private var validatedDraft: BulkDraft? = null
    private var cachedValidation: BulkValidation? = null

    /** Revalidates saved rows only when their immutable list changes, never on each keystroke. */
    fun refresh(state: BulkFlowState): BulkFlowState {
        val draft = state.draft
        val editor = state.editor
        val sameRows =
            draft?.recipients === validatedDraft?.recipients &&
                draft?.service == validatedDraft?.service
        val validation = if (sameRows) cachedValidation else draft?.let(prepare::validate)
        validatedDraft = draft
        cachedValidation = validation
        val editorAmount = editor?.let {
            AmountPolicy.parse(BulkServiceCatalog.quote(it.service, it.product) ?: it.amount)
        }
        val replaces =
            validation?.recipients?.firstOrNull { it.id == editor?.id }?.amountMinorUnits ?: 0
        val previewAmount =
            if (
                editor != null &&
                    editor.recipientReference.isNotBlank() &&
                    prepare.validateRecipient(editor) == null
            )
                editorAmount ?: 0
            else 0
        val amounts = validation?.recipients?.associate { it.id to it.amountMinorUnits }.orEmpty()
        val visible =
            draft?.recipients.orEmpty().mapIndexedNotNull { index, input ->
                if (
                    matchesRecipient(
                        state.recipientQuery,
                        input.name,
                        input.recipientReference,
                        input.provider,
                    )
                )
                    BulkRecipientRow(input, index + 1, amounts[input.id])
                else null
            }
        return state.copy(
            providerPicker =
                state.providerPicker.takeIf {
                    state.screen == BulkScreen.Recipients &&
                        editor?.service == BulkService.Electricity
                } ?: ProviderPickerState(),
            visibleRecipients = visible,
            validation = validation,
            editorProviders = draft?.let { BulkServiceCatalog.providers(it.service) }.orEmpty(),
            editorProducts = draft?.let { BulkServiceCatalog.products(it.service) }.orEmpty(),
            editorAmountMinorUnits = editorAmount,
            previewTotalMinorUnits = (validation?.totalMinorUnits ?: 0) - replaces + previewAmount,
        )
    }

    /**
     * Drops submitted draft aliases and migrates older mixed drafts into separate service lists.
     */
    fun restore(state: BulkFlowState, submittedIds: Set<String>): BulkFlowState {
        val submittedDraft = state.draft?.takeIf { it.id in submittedIds }
        val active = state.draft?.takeUnless { it.id in submittedIds }
        val retained = state.savedDrafts.filterNot { it.draft.id in submittedIds }
        val clean =
            state.copy(
                draft = active,
                importState = null,
                screen =
                    when {
                        submittedDraft != null &&
                            state.screen in
                                listOf(
                                    BulkScreen.Recipients,
                                    BulkScreen.Review,
                                    BulkScreen.Import,
                                ) -> BulkScreen.Progress
                        state.screen == BulkScreen.Import -> BulkScreen.Recipients
                        else -> state.screen
                    },
                batchId = submittedDraft?.id ?: state.batchId,
                editor = state.editor.takeIf { active != null },
                savedDrafts = retained,
            )
        if (active == null) return refresh(clean)
        val services =
            (active.recipients.map { it.service } + listOfNotNull(clean.editor?.service)).distinct()
        if (services.size <= 1)
            return refresh(
                clean.copy(draft = active.copy(service = services.firstOrNull() ?: active.service)),
            )
        val split = services.map { service ->
            BulkSavedDraft(
                active.copy(
                    id = "${active.id}:${service.name}",
                    title = "",
                    service = service,
                    recipients = active.recipients.filter { it.service == service },
                ),
                clean.editor?.takeIf { it.service == service },
            )
        }
        val first = split.first()
        return refresh(
            clean.copy(
                draft = first.draft,
                editor = first.editor,
                savedDrafts = retained + split.drop(1),
                screen = BulkScreen.Services,
            ),
        )
    }

    /** Opens service selection without replaying submitted identities. */
    fun open(state: BulkFlowState, submittedIds: Set<String>): BulkFlowState =
        restore(state, submittedIds)
            .copy(screen = BulkScreen.Services, batchId = null, showErrors = false)

    /**
     * Parks the current service, then resumes the requested service with its exact unfinished
     * input.
     */
    fun chooseService(state: BulkFlowState, service: BulkService): BulkFlowState {
        val parked =
            state.savedDrafts + listOfNotNull(state.draft?.let { BulkSavedDraft(it, state.editor) })
        val selected = parked.lastOrNull { it.draft.service == service }
        val draft = selected?.draft ?: BulkDraft(newId(), service = service)
        val editor = selected?.editor ?: newEditor(draft).takeIf { draft.recipients.isEmpty() }
        return refresh(
            state.copy(
                screen = BulkScreen.Recipients,
                recipientQuery = "",
                providerPicker = ProviderPickerState(),
                selectingRecipients = false,
                selectedRecipientIds = emptySet(),
                draft = draft,
                editor = editor,
                savedDrafts = parked.filterNot { it.draft.service == service },
                batchId = null,
                editorIssue = null,
                showErrors = false,
            ),
        )
    }

    /**
     * Transfers valid individual terms without silently changing a TV price or discarding another
     * edit.
     */
    fun fromPayment(state: BulkFlowState, form: PaymentFormState): BulkFlowState {
        val service =
            BulkService.entries.firstOrNull { it.name == form.input.type.name } ?: return state
        val selected = chooseService(state, service)
        val committed = commitEditor(selected, allowBlank = true)
        if (committed.editorIssue != null) return committed
        if (committed.draft == null) return committed
        val product =
            when (service) {
                BulkService.Data -> form.input.dataPlan
                BulkService.Electricity -> form.input.electricityMeterType.name
                BulkService.TV ->
                    BulkServiceCatalog.products(service)
                        .firstOrNull { BulkServiceCatalog.quote(service, it) == form.input.amount }
                        .orEmpty()
                else -> BulkServiceCatalog.products(service).firstOrNull().orEmpty()
            }
        val input =
            BulkRecipientInput(
                form.requestId,
                recipientReference = form.input.recipient,
                provider = form.input.provider,
                service = service,
                amount = form.input.amount,
                product = product,
            )
        // Stable form identity replaces a previously transferred row instead of adding it twice.
        val withInput = committed.copy(editor = input)
        val saved = commitEditor(withInput, allowBlank = false)
        if (saved.editorIssue != null) return refresh(saved)
        return addAnother(saved)
    }

    /** Applies one presentation action; payment commands remain with the ViewModel. */
    fun reduce(state: BulkFlowState, intent: BulkIntent): BulkFlowState =
        refresh(
            when (intent) {
                is BulkIntent.ProviderPicker ->
                    state.copy(
                        providerPicker = reduceProviderPicker(state.providerPicker, intent.intent),
                    )
                is BulkIntent.SearchRecipients -> state.copy(recipientQuery = intent.query.take(80))
                BulkIntent.ToggleRecipientSelection ->
                    state.copy(
                        selectingRecipients = !state.selectingRecipients,
                        selectedRecipientIds = emptySet(),
                    )
                is BulkIntent.SelectRecipient ->
                    state.copy(
                        selectedRecipientIds =
                            if (intent.id in state.selectedRecipientIds)
                                state.selectedRecipientIds - intent.id
                            else state.selectedRecipientIds + intent.id,
                    )
                BulkIntent.SelectVisibleRecipients ->
                    state.copy(
                        selectedRecipientIds =
                            state.selectedRecipientIds +
                                state.visibleRecipients.map { it.input.id },
                    )
                BulkIntent.RequestRemoveSelected ->
                    state.copy(confirmingRemoval = state.selectedRecipientIds.isNotEmpty())
                is BulkIntent.RemoveSelected -> removeSelected(state, intent.confirmed)
                is BulkIntent.ChooseService -> chooseService(state, intent.service)
                BulkIntent.AddAnother -> addAnother(state)
                BulkIntent.SaveRecipient -> commitEditor(state, allowBlank = true)
                is BulkIntent.EditRecipient -> editRecipient(state, intent.id)
                is BulkIntent.RecipientValue -> updateEditor(state, intent.value)
                is BulkIntent.RemoveRecipient -> removeRecipient(state, intent.id)
                BulkIntent.LoadSamples -> appendSamples(state)
                is BulkIntent.SimulateFailure ->
                    state.copy(draft = state.draft?.copy(simulateFailure = intent.enabled))
                BulkIntent.Review -> review(state)
                BulkIntent.ReviewRetry -> state.copy(screen = BulkScreen.RetryReview)
                is BulkIntent.OpenBatch ->
                    state.copy(
                        screen = BulkScreen.Progress,
                        batchId = intent.id,
                        recipientQuery = "",
                        providerPicker = ProviderPickerState(),
                        filter = BulkRecipientFilter.All,
                    )
                is BulkIntent.Filter -> state.copy(filter = intent.value)
                BulkIntent.Back ->
                    state.copy(
                        screen =
                            when (state.screen) {
                                BulkScreen.Import,
                                BulkScreen.Review -> BulkScreen.Recipients
                                BulkScreen.Recipients -> BulkScreen.Services
                                BulkScreen.RetryReview -> BulkScreen.Progress
                                else -> null
                            },
                        editorIssue = null,
                    )
                BulkIntent.Close,
                BulkIntent.History -> state.copy(screen = null)
                else -> state
            },
        )

    /**
     * Reuses only service terms; account identifiers and names never carry over to a new recipient.
     */
    private fun newEditor(
        draft: BulkDraft,
        previous: BulkRecipientInput? = draft.recipients.lastOrNull(),
    ): BulkRecipientInput =
        previous?.copy(id = newId(), name = "", recipientReference = "")
            ?: BulkRecipientInput(
                newId(),
                service = draft.service,
                provider = BulkServiceCatalog.providers(draft.service).first(),
                product = BulkServiceCatalog.products(draft.service).firstOrNull().orEmpty(),
            )

    /** Adds a fresh row only after the current row is valid and below the demo limit. */
    private fun addAnother(state: BulkFlowState): BulkFlowState {
        val saved = commitEditor(state, allowBlank = false)
        if (saved.editorIssue != null) return saved
        val draft = saved.draft ?: return saved
        if (draft.recipients.size >= BulkLimits.MAX_RECIPIENTS)
            return saved.copy(editorIssue = BulkInputIssue.RecipientLimit)
        return refresh(
            saved.copy(
                editor = newEditor(draft, state.editor ?: draft.recipients.lastOrNull()),
                recipientQuery = "",
                providerPicker = ProviderPickerState(),
                selectingRecipients = false,
                selectedRecipientIds = emptySet(),
            ),
        )
    }

    /**
     * Expands a saved row in the same list; invalid unfinished input remains visible for
     * correction.
     */
    private fun editRecipient(state: BulkFlowState, id: String): BulkFlowState {
        if (state.editor?.id == id) return state
        val saved = commitEditor(state, allowBlank = true)
        if (saved.editorIssue != null) return saved
        return saved.copy(
            editor = saved.draft?.recipients?.find { it.id == id },
            providerPicker = ProviderPickerState(),
            editorIssue = null,
        )
    }

    /**
     * Fixes the service to the draft and bounds raw input without modifying account-number zeros.
     */
    private fun updateEditor(state: BulkFlowState, value: BulkRecipientInput): BulkFlowState {
        val current = state.editor ?: return state
        return state.copy(
            editor =
                value.copy(
                    id = current.id,
                    service = current.service,
                    name = value.name.take(40),
                    recipientReference = value.recipientReference.take(BulkLimits.MAX_RECIPIENTS),
                    amount = value.amount.take(10),
                ),
            editorIssue = null,
        )
    }

    /**
     * Removes the active edit too when explicitly requested, then leaves a fresh row for an empty
     * list.
     */
    private fun removeRecipient(state: BulkFlowState, id: String): BulkFlowState {
        val draft = state.draft ?: return state
        val updated = draft.copy(recipients = draft.recipients.filterNot { it.id == id })
        val editor = state.editor?.takeUnless { it.id == id }
        return state.copy(
            draft = updated,
            editor = editor ?: newEditor(updated),
            providerPicker = ProviderPickerState(),
            editorIssue = null,
        )
    }

    /**
     * Removes only selected unsubmitted rows after confirmation, preserving other service drafts.
     */
    private fun removeSelected(state: BulkFlowState, confirmed: Boolean): BulkFlowState {
        if (!confirmed || !state.confirmingRemoval) return state.copy(confirmingRemoval = false)
        val draft = state.draft ?: return state.copy(confirmingRemoval = false)
        return state.copy(
            draft =
                draft.copy(
                    recipients = draft.recipients.filterNot { it.id in state.selectedRecipientIds },
                ),
            editor = state.editor?.takeUnless { it.id in state.selectedRecipientIds },
            selectedRecipientIds = emptySet(),
            selectingRecipients = false,
            confirmingRemoval = false,
        )
    }

    /**
     * Saves valid inline terms; an untouched extra row may be omitted when reviewing existing
     * recipients.
     */
    private fun commitEditor(state: BulkFlowState, allowBlank: Boolean): BulkFlowState {
        val editor = state.editor ?: return state
        val draft = state.draft ?: return state
        val isNew = draft.recipients.none { it.id == editor.id }
        if (allowBlank && isNew && editor.recipientReference.isBlank() && editor.name.isBlank())
            return state.copy(editor = null, editorIssue = null)
        val issue = prepare.validateRecipient(editor)
        if (issue != null) return state.copy(editorIssue = issue)
        val edited = draft.recipients.map { if (it.id == editor.id) editor else it }
        val rows = if (isNew) edited + editor else edited
        if (rows.size > BulkLimits.MAX_RECIPIENTS)
            return state.copy(editorIssue = BulkInputIssue.RecipientLimit)
        val updated = draft.copy(recipients = rows)
        val invalid = prepare.validate(updated).issues.firstOrNull { it.recipientId == editor.id }
        if (invalid != null) return state.copy(editorIssue = invalid.kind)
        return state.copy(draft = updated, editor = null, editorIssue = null)
    }

    /**
     * Includes the final entered recipient; an empty extra row never blocks a valid existing list.
     */
    private fun review(state: BulkFlowState): BulkFlowState {
        val saved = commitEditor(state, allowBlank = state.draft?.recipients?.isNotEmpty() == true)
        if (saved.editorIssue != null) return saved
        val valid = saved.draft?.let { prepare.validate(it).valid } == true
        return saved.copy(
            screen = if (valid) BulkScreen.Review else BulkScreen.Recipients,
            showErrors = true,
            recipientQuery = "",
        )
    }

    /**
     * Adds same-service demo examples once, preserving unfinished manual input on validation
     * errors.
     */
    private fun appendSamples(state: BulkFlowState): BulkFlowState {
        val saved = commitEditor(state, allowBlank = true)
        if (saved.editorIssue != null) return saved
        val draft = saved.draft ?: return saved
        val samples =
            BulkServiceCatalog.sampleRecipients(draft.service, newId).filter { sample ->
                draft.recipients.none {
                    it.recipientReference == sample.recipientReference &&
                        it.provider == sample.provider
                }
            }
        return saved.copy(
            draft =
                draft.copy(
                    recipients = (draft.recipients + samples).take(BulkLimits.MAX_RECIPIENTS),
                ),
            editor = null,
        )
    }
}
