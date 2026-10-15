package com.kesofty.axiapay

import androidx.lifecycle.SavedStateHandle
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.*
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import java.time.Clock
import org.junit.Assert.*
import org.junit.Test

/** Uses Android JSON and SavedStateHandle to verify real bulk snapshot compatibility. */
class BulkRestorationTest {
    /** An interrupted row, its reservation and unsaved editor text restore without execution. */
    @Test
    fun pendingBatchAndUnfinishedEditorRoundTrip() =
        kotlinx.coroutines.runBlocking {
            val handle = SavedStateHandle()
            val clock = Clock.systemUTC()
            val prepare = PrepareBulkBatch(PreparePayment(clock), clock)
            val repository =
                DemoSessionRepository(SavedStateSessionStore(handle), DemoSession(5_000_000))
            var rowId = 0
            val draft =
                BulkDraft(
                    "persisted-batch",
                    "My batch",
                    BulkServiceCatalog.sampleRecipients(BulkService.Airtime) { "row-${rowId++}" },
                )
            QueueDemoBulkBatch(repository, prepare)(draft)
            repository.transact { DemoBulkPolicy.beginNext(it, draft.id) }
            val presenter = BulkFormPresenter(prepare) { "new-row" }
            val flow =
                presenter.refresh(
                    BulkFlowState(
                        screen = BulkScreen.Recipients,
                        draft = draft.copy(id = "next-batch"),
                        editor = draft.recipients[2].copy(name = "Unfinished meter"),
                        batchId = draft.id,
                        savedDrafts =
                            listOf(
                                BulkSavedDraft(
                                    BulkDraft("data-draft", service = BulkService.Data),
                                    BulkRecipientInput(
                                        "partial-data",
                                        service = BulkService.Data,
                                        recipientReference = "0802",
                                        product = "8 GB",
                                    ),
                                ),
                            ),
                    ),
                )
            SavedNavigationStore(handle).save(NavigationState(tab = 1, bulk = flow))
            val restoredHandle =
                SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
            val restored =
                DemoSessionRepository(SavedStateSessionStore(restoredHandle), DemoSession(0))
            val navigation = SavedNavigationStore(restoredHandle).load()!!
            assertEquals(repository.session.value, restored.session.value)
            assertEquals(flow, presenter.refresh(navigation.bulk))
            assertEquals(
                BulkRecipientStatus.Processing,
                restored.session.value.batches.single().recipients.first().status,
            )
            assertEquals(4_400_000L, restored.session.value.balance)
            assertTrue(restored.session.value.payments.isEmpty())
        }
}
