package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.*
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Repeatable scale probes use synthetic identifiers and never invoke external payment services. */
class BulkScaleTest {
    /** Measures CPU work independently from document IO and Compose rendering. */
    @Test
    fun measureImportPreparation() {
        val fixture = DemoTestFixture()
        var identity = 0
        val prepare =
            PrepareRecipientImport(
                RecipientImportRepository { error("No IO") },
                fixture.prepareBulk,
            ) {
                "row-${identity++}"
            }
        for (count in listOf(100, 200, 1000)) {
            val rows =
                listOf(
                    ImportTableRow(
                        1,
                        listOf("name", "recipient", "provider", "amount").map(::ImportCell),
                    ),
                ) +
                    (1..count).map { ordinal ->
                        ImportTableRow(
                            ordinal + 1,
                            listOf(
                                    "Demo $ordinal",
                                    "0805${ordinal.toString().padStart(7, '0')}",
                                    "MTN",
                                    "100",
                                )
                                .map(::ImportCell),
                        )
                    }
            val document = ImportDocument("scale.csv", null, rows)
            val start = System.nanoTime()
            val result =
                prepare.preview(document, BulkDraft("scale"), null) as RecipientImportResult.Ready
            println(
                "BULK_SCALE count=$count valid=${result.preview.rows.count { it.valid }} prepareMs=${(System.nanoTime()-start)/1_000_000}",
            )
        }
    }

    /** Every supported service accepts exactly 1,000 reviewed rows and rejects the next row. */
    @Test
    fun allServicesRespectCapacityAndExactTotals() {
        val fixture = DemoTestFixture()
        for (service in BulkService.entries) {
            val draft = largeDraft(service)
            val validation = fixture.prepareBulk.validate(draft)
            assertTrue("$service: ${validation.issues}", validation.valid)
            assertEquals(1000, validation.recipients.size)
            assertEquals(
                validation.recipients.sumOf { it.amountMinorUnits },
                validation.totalMinorUnits,
            )
            assertTrue(
                fixture.prepareBulk
                    .validate(
                        draft.copy(
                            recipients =
                                draft.recipients + draft.recipients.last().copy(id = "overflow"),
                        ),
                    )
                    .issues
                    .any { it.kind == BulkInputIssue.RecipientLimit },
            )
        }
    }

    /** Interrupted grouped execution retains reservations; resume/retry never double-debits. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun thousandRecipientRecoveryRetainsEveryOutcome() = runTest {
        val fixture = DemoTestFixture(MemorySessionStore(DemoSession(20_000_000)))
        val draft = largeDraft(BulkService.Airtime)
        fixture.queueBulk(draft).success()
        val job = launch { fixture.runBulk(draft.id) }
        runCurrent()
        assertEquals(
            25,
            fixture.session.batches.single().recipients.count {
                it.status == BulkRecipientStatus.Processing
            },
        )
        job.cancelAndJoin()
        val restored = DemoTestFixture(fixture.store)
        assertEquals(fixture.session, restored.session)
        restored.runBulk(draft.id).success()
        val batch = restored.session.batches.single()
        assertEquals(999, batch.successfulCount)
        assertEquals(1, batch.failedCount)
        assertEquals(999, restored.session.payments.size)
        assertEquals(20_000_000 - batch.paidMinorUnits, restored.session.balance)
        restored.queueBulk(draft).success()
        restored.retryBulk(draft.id).success()
        restored.runBulk(draft.id).success()
        restored.runBulk(draft.id).success()
        assertEquals(1000, restored.session.payments.size)
        assertEquals(1000, restored.session.payments.map { it.id }.distinct().size)
        assertEquals(20_000_000 - draft.recipients.size * 10_000L, restored.session.balance)
    }

    /** Search and bulk removal never change the full total until the removal is confirmed. */
    @Test
    fun largeDraftSearchEditAndConfirmedSelectionRemainConsistent() {
        val fixture = DemoTestFixture()
        val presenter = BulkFormPresenter(fixture.prepareBulk) { "new-id" }
        var state =
            presenter.refresh(
                BulkFlowState(
                    screen = BulkScreen.Recipients,
                    draft = largeDraft(BulkService.Airtime),
                ),
            )
        val total = state.previewTotalMinorUnits
        state = presenter.reduce(state, BulkIntent.SearchRecipients("08050001000"))
        assertEquals(1, state.visibleRecipients.size)
        assertEquals(total, state.previewTotalMinorUnits)
        state = presenter.reduce(state, BulkIntent.EditRecipient("row-1000"))
        state =
            presenter.reduce(
                state,
                BulkIntent.RecipientValue(state.editor!!.copy(amount = "125.25")),
            )
        assertEquals(total + 2525, state.previewTotalMinorUnits)
        state = presenter.reduce(state, BulkIntent.Review)
        assertEquals(total + 2525, state.validation!!.totalMinorUnits)
        state = presenter.reduce(state, BulkIntent.Back)
        state = presenter.reduce(state, BulkIntent.SearchRecipients("08050001000"))
        state = presenter.reduce(state, BulkIntent.ToggleRecipientSelection)
        state = presenter.reduce(state, BulkIntent.SelectVisibleRecipients)
        state = presenter.reduce(state, BulkIntent.RequestRemoveSelected)
        assertEquals(1000, state.draft!!.recipients.size)
        state = presenter.reduce(state, BulkIntent.RemoveSelected(true))
        assertEquals(999, state.draft!!.recipients.size)
        assertEquals(total - 10000, state.previewTotalMinorUnits)
    }

    /**
     * Synthetic identifiers are unique text values and all prices use the retained sample catalog.
     */
    private fun largeDraft(service: BulkService): BulkDraft =
        BulkDraft(
            "large-${service.name}",
            service = service,
            recipients =
                (1..1000).map { ordinal ->
                    BulkRecipientInput(
                        "row-$ordinal",
                        "Demo recipient $ordinal",
                        "0805${ordinal.toString().padStart(7, '0')}",
                        BulkServiceCatalog.providers(service).first(),
                        service,
                        "100",
                        BulkServiceCatalog.products(service).firstOrNull().orEmpty(),
                    )
                },
        )
}
