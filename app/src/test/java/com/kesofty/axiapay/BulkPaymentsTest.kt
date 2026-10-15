package com.kesofty.axiapay

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises reservation, recovery and validation contracts against the real demo repository. */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkPaymentsTest {
    /**
     * Same-service lists keep exact quotes; a mixed new submission is rejected before reservation.
     */
    @Test
    fun servicesValidateSeparatelyAndFixedPricesCannotBeOverridden() =
        kotlinx.coroutines.test.runTest {
            val f = DemoTestFixture()
            BulkService.entries.forEach { service ->
                var index = 0
                val rows = BulkServiceCatalog.sampleRecipients(service) { "row-${index++}" }
                val draft = BulkDraft("batch-$service", recipients = rows, service = service)
                val changed =
                    draft.copy(
                        recipients =
                            rows.map {
                                if (service in listOf(BulkService.Data, BulkService.TV))
                                    it.copy(amount = "1")
                                else it
                            },
                    )
                val prepared = f.prepareBulk(changed).success()
                assertEquals(setOf(service), prepared.recipients.map { it.details.service }.toSet())
                assertEquals(
                    f.prepareBulk(draft).success().totalMinorUnits,
                    prepared.totalMinorUnits,
                )
                assertFalse(
                    f.prepareBulk
                        .validate(
                            draft.copy(
                                recipients = rows.map { it.copy(recipientReference = "123") },
                            ),
                        )
                        .valid,
                )
            }
            val mixed =
                f.bulkDraft()
                    .copy(
                        recipients =
                            f.bulkDraft().recipients +
                                BulkServiceCatalog.sampleRecipients(BulkService.Data) { "data" }
                                    .take(1),
                    )
            assertTrue(
                f.prepareBulk.validate(mixed).issues.any {
                    it.kind == BulkInputIssue.ServiceMismatch
                },
            )
            assertTrue(f.queueBulk(mixed) is Outcome.Failure)
            val unnamed =
                f.bulkDraft()
                    .copy(
                        title = "",
                        recipients = f.bulkDraft().recipients.map { it.copy(name = "") },
                    )
            assertTrue(f.prepareBulk.validate(unnamed).valid)
            assertEquals("Airtime", f.prepareBulk(unnamed).success().title)
            assertEquals(
                "08030000001",
                f.prepareBulk(unnamed).success().recipients.first().details.name,
            )
        }

    /**
     * Normalized phone aliases cannot add the same service twice, and size/name limits are
     * explicit.
     */
    @Test
    fun duplicatesAndMalformedInputsCannotReachTheQueue() =
        kotlinx.coroutines.test.runTest {
            val f = DemoTestFixture()
            val first = f.bulkDraft().recipients.first()
            val duplicate = first.copy(id = "alias", recipientReference = "+234 803 000 0001")
            val draft = f.bulkDraft().copy(recipients = listOf(first, duplicate))
            assertTrue(
                f.prepareBulk.validate(draft).issues.any {
                    it.kind == BulkInputIssue.DuplicateRecipient
                },
            )
            assertTrue(f.queueBulk(draft) is Outcome.Failure)
            assertTrue(
                f.prepareBulk
                    .validate(draft.copy(recipients = (1..1001).map { first.copy(id = "$it") }))
                    .issues
                    .any { it.kind == BulkInputIssue.RecipientLimit },
            )
            assertEquals(
                BulkInputIssue.Amount,
                f.prepareBulk.validateRecipient(first.copy(amount = "1.001")),
            )
            assertEquals(
                BulkInputIssue.Provider,
                f.prepareBulk.validateRecipient(first.copy(provider = "Unknown")),
            )
        }

    /** Queueing reserves once, failed rows refund once, and retry touches only known failures. */
    @Test
    fun partialCompletionAndRetryConserveWalletFunds() = runTest {
        val f = DemoTestFixture()
        val before = f.session.balance
        val draft = f.bulkDraft()
        val batch = f.queueBulk(draft).success()
        assertEquals(before - batch.totalMinorUnits, f.session.balance)
        f.queueBulk(draft).success()
        assertEquals(before - batch.totalMinorUnits, f.session.balance)
        f.runBulk(batch.id).success()
        val partial = f.session.batches.first()
        assertEquals(BulkBatchStatus.PartiallyCompleted, partial.status)
        assertEquals(3, partial.successfulCount)
        assertEquals(0L, partial.reservedMinorUnits)
        assertEquals(before - partial.paidMinorUnits, f.session.balance)
        val successes = partial.recipients.filter { it.status == BulkRecipientStatus.Successful }
        f.retryBulk(batch.id).success()
        f.retryBulk(batch.id).success()
        f.runBulk(batch.id).success()
        f.runBulk(batch.id).success()
        val completed = f.session.batches.first()
        assertEquals(BulkBatchStatus.Completed, completed.status)
        assertEquals(successes, completed.recipients.take(3))
        assertEquals(2, completed.recipients.last().attempts)
        assertEquals(before - batch.totalMinorUnits, f.session.balance)
        assertEquals(4, f.session.payments.count { it.id.startsWith(batch.id + ":") })
        assertEquals(
            Outcome.Failure(DemoError.RequestConflict),
            f.queueBulk(draft.copy(title = "Different terms")),
        )
    }

    /**
     * Insufficient funds and failed persistence leave both batch and wallet snapshots unchanged.
     */
    @Test
    fun rejectedReservationsAreAtomic() =
        kotlinx.coroutines.test.runTest {
            val f = DemoTestFixture(MemorySessionStore(DemoSession(balance = 1_000)))
            val before = f.session
            assertEquals(Outcome.Failure(DemoError.InsufficientFunds), f.queueBulk(f.bulkDraft()))
            assertEquals(before, f.session)
            val funded = DemoTestFixture()
            val fundedBefore = funded.session
            funded.store.failSave = true
            assertTrue(
                (funded.queueBulk(funded.bulkDraft()) as Outcome.Failure).error
                    is DemoError.Storage,
            )
            assertEquals(fundedBefore, funded.session)
        }

    /**
     * Cancellation and offline recovery retain reservations; only an explicit run makes progress.
     */
    @Test
    fun interruptedProcessingRestoresWithoutReplayAndResumesSameAttempt() = runTest {
        val f = DemoTestFixture()
        val batch = f.queueBulk(f.bulkDraft()).success()
        val job = launch { f.runBulk(batch.id) }
        runCurrent()
        assertEquals(
            BulkRecipientStatus.Processing,
            f.session.batches.first().recipients.first().status,
        )
        job.cancel()
        runCurrent()
        val saved = f.session
        val restored = DemoTestFixture(f.store)
        advanceUntilIdle()
        assertEquals(saved, restored.session)
        UpdateConnectionState(restored.repository, restored.clock)(true)
        assertEquals(Outcome.Failure(DemoError.OnlineRequired), restored.runBulk(batch.id))
        UpdateConnectionState(restored.repository, restored.clock)(false)
        assertEquals(saved.batches, restored.session.batches)
        assertEquals(saved.balance, restored.session.balance)
        restored.runBulk(batch.id).success()
        assertEquals(1, restored.session.batches.first().recipients.first().attempts)
        assertEquals(3, restored.session.batches.first().successfulCount)
    }

    /** Duplicate completions and stale attempts cannot release or spend funds more than once. */
    @Test
    fun duplicateFinishesAndSnapshotAliasesCannotCorruptProgress() =
        kotlinx.coroutines.test.runTest {
            val f = DemoTestFixture()
            val batch = f.queueBulk(f.bulkDraft()).success()
            val recipient =
                f.repository.transact { DemoBulkPolicy.beginNext(it, batch.id) }.success()!!
            f.repository
                .transact {
                    DemoBulkPolicy.finish(it, batch.id, recipient.details.id, recipient.attempts)
                }
                .success()
            val completed = f.session
            f.repository
                .transact {
                    DemoBulkPolicy.finish(it, batch.id, recipient.details.id, recipient.attempts)
                }
                .success()
            assertEquals(completed, f.session)
            val exposed = f.session.batches.first().recipients as MutableList<BulkRecipient>
            assertThrows(UnsupportedOperationException::class.java) { exposed.clear() }
            assertEquals(completed, f.session)
        }
}
