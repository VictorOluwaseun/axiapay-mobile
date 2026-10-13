package com.kesofty.axiapay

import com.kesofty.axiapay.data.DemoSessionRepository
import com.kesofty.axiapay.data.DemoSessionStore
import com.kesofty.axiapay.domain.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Verifies repository ownership of snapshots and atomic publication at the storage boundary. */
class SessionSnapshotTest {
    /** Caller-owned lists from seed, storage and transactions cannot mutate published snapshots. */
    @Test
    fun `incoming collection aliases cannot mutate repository state`() {
        val fixture = DemoTestFixture()
        val payments = fixture.session.payments.toMutableList()
        val plans = fixture.session.plans.toMutableList()
        val outbox = fixture.session.smsOutbox.toMutableList()
        val seed = fixture.session.copy(payments = payments, plans = plans, smsOutbox = outbox)
        val seeded = DemoSessionRepository(MemorySessionStore(), seed)
        val loaded = DemoSessionRepository(MemorySessionStore(seed), fixture.session)
        val committed = DemoSessionRepository(MemorySessionStore(), fixture.session)
        committed.transact { Outcome.Success(SessionChange(seed, Unit)) }
        val expected = seeded.session.value
        payments.clear()
        plans.clear()
        outbox.add(
            com.kesofty.axiapay.application.PrepareSmsPayment(
                    fixture.repository,
                    fixture.prepare,
                    fixture.clock,
                )(fixture.input(), "alias-request", 1)
                .success(),
        )
        listOf(seeded, loaded, committed).forEach { repository ->
            assertEquals(expected, repository.session.value)
        }
    }

    /** Even a consumer's explicit mutable cast cannot change any published collection. */
    @Test
    fun `all exposed collections reject mutation`() {
        val snapshot = DemoTestFixture().session
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.payments as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.plans as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.smsOutbox as MutableList).clear()
        }
    }

    /** Cancellation is propagated unchanged and leaves the last committed snapshot visible. */
    @Test
    fun `cancelled storage never publishes an uncommitted value`() {
        val fixture = DemoTestFixture()
        val cancellation = CancellationException("Owner cancelled")
        val store =
            object : DemoSessionStore {
                /** Starts from the injected seed. */
                override fun load(): DemoSession? = null

                /** Simulates cancellation before storage can commit. */
                override fun save(session: DemoSession) {
                    throw cancellation
                }
            }
        val repository = DemoSessionRepository(store, fixture.session)
        val before = repository.session.value
        val thrown =
            assertThrows(CancellationException::class.java) {
                repository.transact { Outcome.Success(SessionChange(it.copy(hidden = true), Unit)) }
            }
        assertSame(cancellation, thrown)
        assertEquals(before, repository.session.value)
    }
}
