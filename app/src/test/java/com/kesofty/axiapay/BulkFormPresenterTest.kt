package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import org.junit.Assert.*
import org.junit.Test

/** Checks quick entry, draft isolation and exact totals without depending on Compose rendering. */
class BulkFormPresenterTest {
    /** Gives draft and row identities a deterministic, unique sequence. */
    private fun presenter(): BulkFormPresenter {
        var id = 0
        return BulkFormPresenter(DemoTestFixture().prepareBulk) { "id-${id++}" }
    }

    /**
     * Repeated additions inherit terms, include the last row in review and never duplicate an edit.
     */
    @Test
    fun quickAddAndInlineEditKeepExactTotals() {
        val p = presenter()
        var state = p.chooseService(BulkFlowState(), BulkService.Airtime)
        state =
            p.reduce(
                state,
                BulkIntent.RecipientValue(
                    state.editor!!.copy(
                        recipientReference = "08030000001",
                        amount = "1200.25",
                        provider = "Airtel",
                    ),
                ),
            )
        assertEquals(120_025L, state.previewTotalMinorUnits)
        state = p.reduce(state, BulkIntent.AddAnother)
        assertEquals("Airtel", state.editor!!.provider)
        assertEquals("1200.25", state.editor!!.amount)
        assertEquals("", state.editor!!.recipientReference)
        assertEquals(120_025L, state.previewTotalMinorUnits)
        state =
            p.reduce(
                state,
                BulkIntent.RecipientValue(state.editor!!.copy(recipientReference = "08030000002")),
            )
        state = p.reduce(state, BulkIntent.Review)
        assertEquals(BulkScreen.Review, state.screen)
        assertEquals(240_050L, state.validation!!.totalMinorUnits)
        val firstId = state.draft!!.recipients.first().id
        state = p.reduce(state, BulkIntent.Back)
        state = p.reduce(state, BulkIntent.EditRecipient(firstId))
        state = p.reduce(state, BulkIntent.RecipientValue(state.editor!!.copy(amount = "2000.50")))
        assertEquals(320_075L, state.previewTotalMinorUnits)
        state = p.reduce(state, BulkIntent.Review)
        assertEquals(2, state.draft!!.recipients.size)
        assertEquals(320_075L, state.validation!!.totalMinorUnits)
    }

    /** Empty extra rows do not block review; malformed or duplicate entered rows do. */
    @Test
    fun reviewIgnoresOnlyAnUnusedNewRow() {
        val p = presenter()
        var state = p.chooseService(BulkFlowState(), BulkService.Airtime)
        state =
            p.reduce(
                state,
                BulkIntent.RecipientValue(state.editor!!.copy(recipientReference = "08030000001")),
            )
        state = p.reduce(state, BulkIntent.AddAnother)
        val blank = state
        state =
            p.reduce(
                state,
                BulkIntent.RecipientValue(
                    state.editor!!.copy(recipientReference = "+234 803 000 0001"),
                ),
            )
        state = p.reduce(state, BulkIntent.Review)
        assertEquals(BulkInputIssue.DuplicateRecipient, state.editorIssue)
        assertEquals(BulkScreen.Recipients, state.screen)
        assertEquals(1, p.reduce(blank, BulkIntent.Review).draft!!.recipients.size)
        state = p.reduce(blank, BulkIntent.EditRecipient(blank.draft!!.recipients.single().id))
        state =
            p.reduce(state, BulkIntent.RecipientValue(state.editor!!.copy(recipientReference = "")))
        assertEquals(
            BulkInputIssue.RecipientReference,
            p.reduce(state, BulkIntent.Review).editorIssue,
        )
    }

    /**
     * Switching services parks exact unfinished input and cannot mix service types through an
     * intent.
     */
    @Test
    fun serviceDraftsRemainIndependentAndRestoreWithoutSubmission() {
        val p = presenter()
        var state = p.chooseService(BulkFlowState(), BulkService.Data)
        state =
            p.reduce(
                state,
                BulkIntent.RecipientValue(
                    state.editor!!.copy(recipientReference = "0802", product = "20 GB"),
                ),
            )
        val original = state
        state = p.chooseService(state, BulkService.TV)
        state =
            p.reduce(
                state,
                BulkIntent.RecipientValue(state.editor!!.copy(recipientReference = "01234567890")),
            )
        state = p.chooseService(p.restore(state, emptySet()), BulkService.Data)
        assertEquals(original.draft, state.draft)
        assertEquals(original.editor, state.editor)
        val sameService =
            p.reduce(
                state,
                BulkIntent.RecipientValue(state.editor!!.copy(service = BulkService.TV)),
            )
        assertEquals(BulkService.Data, sameService.editor!!.service)
        val resumedTv = p.chooseService(state, BulkService.TV)
        assertEquals("01234567890", resumedTv.editor!!.recipientReference)
    }

    /**
     * A previous individual form becomes the first recipient with unchanged account, plan and
     * quote.
     */
    @Test
    fun individualPaymentTransfersOnceAndKeepsDefaults() {
        val f = DemoTestFixture()
        val p = presenter()
        val form =
            PaymentFormState(
                "individual",
                "Data",
                PaymentInput(
                    PaymentType.Data,
                    provider = "Airtel",
                    recipient = "08030000001",
                    amount = "3500",
                    dataPlan = "8 GB",
                    date = java.time.LocalDate.of(2026, 10, 15),
                ),
            )
        var state = p.fromPayment(BulkFlowState(), form)
        assertEquals(1, state.draft!!.recipients.size)
        assertEquals("individual", state.draft!!.recipients.single().id)
        assertEquals("8 GB", state.editor!!.product)
        assertEquals(350_000L, state.previewTotalMinorUnits)
        state = p.fromPayment(state, form)
        assertEquals(1, state.draft!!.recipients.size)
        assertEquals(350_000L, f.prepareBulk.validate(state.draft!!).totalMinorUnits)
    }

    /**
     * Legacy mixed drafts split into service drafts, while already submitted aliases are discarded.
     */
    @Test
    fun legacyDraftMigrationDoesNotLoseRecipientsOrReplaySubmittedRequests() {
        val p = presenter()
        var index = 0
        val rows =
            BulkService.entries.map {
                BulkServiceCatalog.sampleRecipients(it) { "legacy-${index++}" }.first()
            }
        val old =
            BulkFlowState(
                screen = BulkScreen.Recipients,
                draft = BulkDraft("old", "Old list", rows),
                editor = rows[2].copy(amount = "9000"),
            )
        val migrated = p.restore(old, emptySet())
        assertEquals(3, migrated.savedDrafts.size)
        val electricity = p.chooseService(migrated, BulkService.Electricity)
        assertEquals("9000", electricity.editor!!.amount)
        assertEquals(1, electricity.draft!!.recipients.size)
        assertNull(p.restore(old, setOf("old")).draft)
    }
}
