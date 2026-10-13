package com.kesofty.axiapay

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.data.*
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import java.time.Clock
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotRestorationTest {
    @Test
    fun freshRepositoryRestoresSnapshotsAndDeduplicatesCommittedRequest() {
        val handle = SavedStateHandle()
        val clock = Clock.systemDefaultZone()
        val seed = DemoSessionSeed.create(LocalDate.now(clock))
        val prepare = PreparePayment(clock)
        val repository = DemoSessionRepository(SavedStateSessionStore(handle), seed)
        val input =
            PaymentInput(
                PaymentType.Airtime,
                recipient = "08000000000",
                amount = "1000",
                date = LocalDate.now(clock).plusDays(1),
                scheduled = true,
                recurrence = Recurrence.Monthly,
            )
        SubmitDemoPayment(repository, prepare, clock)(input, "saved-schedule")
        SetPlanEnabled(repository)("saved-schedule", false)
        SetBalanceVisibility(repository)(true)
        SetPaymentReminders(repository)(false)
        MarkNotificationsRead(repository)()
        val before = repository.session.value
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = DemoSessionRepository(SavedStateSessionStore(restoredHandle), DemoSession(0))
        assertEquals(before, restored.session.value)
        SubmitDemoPayment(restored, prepare, clock)(input, "saved-schedule")
        assertEquals(before, restored.session.value)
        assertEquals(1, restored.session.value.plans.count { it.id == "saved-schedule" })
    }

    @Test
    fun reviewAndReceiptRestoreAsValuesWithoutExecutingCommands() {
        val handle = SavedStateHandle()
        val clock = Clock.systemDefaultZone()
        val presenter = PaymentFormPresenter(PreparePayment(clock), clock)
        var form = presenter.create("Airtime", "saved-request")
        form = presenter.reduce(form, PaymentIntent.Recipient("08000000000"))
        form = presenter.reduce(form, PaymentIntent.Amount("1000"))
        form = presenter.reduce(form, PaymentIntent.Review)
        val store = SavedNavigationStore(handle)
        val navigation = NavigationState(tab = 1, sheet = Sheet.Payment(form), serviceQuery = "air")
        store.save(navigation)
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = requireNotNull(SavedNavigationStore(restoredHandle).load())
        val restoredForm = presenter.refresh((restored.sheet as Sheet.Payment).form)
        assertEquals(navigation, restored.copy(sheet = Sheet.Payment(restoredForm)))
        store.save(NavigationState(sheet = Sheet.Receipt("saved-request")))
        assertEquals(Sheet.Receipt("saved-request"), store.load()?.sheet)
    }

    @Test
    fun offlineOutboxDraftAndSimRestoreWithoutReplay() {
        val handle = SavedStateHandle()
        val clock = Clock.systemDefaultZone()
        val repository = DemoSessionRepository(SavedStateSessionStore(handle), DemoSession(100_000))
        val prepare = PreparePayment(clock)
        val presenter = PaymentFormPresenter(prepare, clock)
        var form = presenter.create("Airtime", "sms-persist")
        form = presenter.reduce(form, PaymentIntent.Recipient("08000000000"))
        form = presenter.reduce(form, PaymentIntent.Amount("1000"))
        form = presenter.reduce(form, PaymentIntent.Sim(2))
        UpdateConnectionState(repository, clock)(true)
        PrepareSmsPayment(repository, prepare, clock)(form.input, form.requestId, form.smsSim)
        SendDemoSms(repository, clock)(form.requestId, false)
        val nav = SavedNavigationStore(handle)
        nav.save(NavigationState(sheet = Sheet.Sms(form.requestId), draft = form))
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = DemoSessionRepository(SavedStateSessionStore(restoredHandle), DemoSession(0))
        assertEquals(repository.session.value, restored.session.value)
        val restoredNav = requireNotNull(SavedNavigationStore(restoredHandle).load())
        assertEquals(Sheet.Sms(form.requestId), restoredNav.sheet)
        assertEquals(form, presenter.refresh(requireNotNull(restoredNav.draft)))
        assertEquals(100_000, restored.session.value.balance)
        assertTrue(restored.session.value.payments.isEmpty())
    }

    /**
     * Saved-state round trips retain overrides, while older snapshots retain automatic behaviour.
     */
    @Test
    fun connectionPreferenceRestoresAndOlderSnapshotsDefaultToAutomatic() {
        val handle = SavedStateHandle()
        val store = SavedStateSessionStore(handle)
        val original =
            DemoSession(100_000, offline = true, connectionMode = ConnectionMode.ForceOffline)
        store.save(original)
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        assertEquals(original, SavedStateSessionStore(restoredHandle).load())
        val snapshotKey = handle.keys().single()
        val oldSnapshot = org.json.JSONObject(handle.get<String>(snapshotKey)!!)
        oldSnapshot.remove("connectionMode")
        handle[snapshotKey] = oldSnapshot.toString()
        assertEquals(ConnectionMode.Automatic, store.load()?.connectionMode)
    }
}
