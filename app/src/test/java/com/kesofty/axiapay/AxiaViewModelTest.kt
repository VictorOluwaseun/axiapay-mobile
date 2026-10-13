package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.domain.ConnectionMode
import com.kesofty.axiapay.domain.PaymentStatus
import com.kesofty.axiapay.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AxiaViewModelTest {
    private val owners = mutableListOf<ViewModelStore>()

    @Before
    fun mainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun cleanUp() {
        owners.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private fun own(vm: AxiaViewModel): AxiaViewModel {
        ViewModelStore().also {
            it.put("axia", vm)
            owners.add(it)
        }
        return vm
    }

    private fun fillAirtime(vm: AxiaViewModel) {
        vm.accept(AxiaIntent.OpenService("Airtime"))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Recipient("08000000000")))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Amount("1000")))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Review))
    }

    @Test
    fun `review changes only presentation and confirm publishes one receipt`() {
        val f = DemoTestFixture()
        val vm = own(f.viewModel())
        val initial = vm.uiState.value.session.balance
        fillAirtime(vm)
        assertTrue((vm.uiState.value.navigation.sheet as Sheet.Payment).form.reviewing)
        assertEquals(initial, vm.uiState.value.session.balance)
        vm.accept(AxiaIntent.ConfirmPayment)
        val receipt = vm.uiState.value.navigation.sheet as Sheet.Receipt
        vm.accept(AxiaIntent.ConfirmPayment)
        assertEquals(receipt, vm.uiState.value.navigation.sheet)
        assertEquals(initial - 100_000, vm.uiState.value.session.balance)
        assertEquals(1, f.session.payments.count { it.id == receipt.paymentId })
    }

    @Test
    fun `restoring a completed receipt cannot replay the payment`() {
        val f = DemoTestFixture()
        val navigation = MemoryNavigationStore()
        val vm = own(f.viewModel(navigation))
        fillAirtime(vm)
        vm.accept(AxiaIntent.ConfirmPayment)
        val before = vm.uiState.value
        val recreated = own(DemoTestFixture(f.store).viewModel(navigation))
        assertEquals(before, recreated.uiState.value)
        recreated.accept(AxiaIntent.ConfirmPayment)
        assertEquals(before, recreated.uiState.value)
    }

    @Test
    fun `form input review and logical request ID survive restoration without a debit`() {
        val f = DemoTestFixture()
        val navigation = MemoryNavigationStore()
        val vm = own(f.viewModel(navigation))
        fillAirtime(vm)
        val form = (vm.uiState.value.navigation.sheet as Sheet.Payment).form
        val restored = own(f.viewModel(navigation))
        assertEquals(form, (restored.uiState.value.navigation.sheet as Sheet.Payment).form)
        assertEquals(12_845_000, restored.uiState.value.session.balance)
    }

    @Test
    fun `invalid inputs cannot advance review or submit`() {
        val f = DemoTestFixture()
        val vm = own(f.viewModel())
        vm.accept(AxiaIntent.OpenService("Airtime"))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Recipient("123")))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Amount("1000")))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Review))
        vm.accept(AxiaIntent.ConfirmPayment)
        val form = (vm.uiState.value.navigation.sheet as Sheet.Payment).form
        assertFalse(form.reviewing)
        assertFalse(form.validation.valid)
        assertEquals(12_845_000, f.session.balance)
    }

    @Test
    fun `failed commit retains review and explicit error can be consumed and retried`() {
        val f = DemoTestFixture()
        val vm = own(f.viewModel())
        fillAirtime(vm)
        f.store.failSave = true
        vm.accept(AxiaIntent.ConfirmPayment)
        assertNotNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.navigation.sheet is Sheet.Payment)
        assertEquals(12_845_000, f.session.balance)
        vm.accept(AxiaIntent.DismissError)
        assertNull(vm.uiState.value.error)
        f.store.failSave = false
        vm.accept(AxiaIntent.ConfirmPayment)
        assertTrue(vm.uiState.value.navigation.sheet is Sheet.Receipt)
        assertEquals(12_745_000, f.session.balance)
    }

    @Test
    fun `preferences notifications and plan toggles update through intents`() {
        val f = DemoTestFixture()
        val vm = own(f.viewModel())
        vm.accept(AxiaIntent.HideBalance(true))
        vm.accept(AxiaIntent.SetReminders(false))
        vm.accept(AxiaIntent.SetPlan("plan-data", false))
        vm.accept(AxiaIntent.OpenService("notifications"))
        assertTrue(vm.uiState.value.session.hidden)
        assertTrue(vm.uiState.value.session.notificationsRead)
        assertFalse(vm.uiState.value.session.reminderEnabled)
        assertFalse(vm.uiState.value.session.plans.first().enabled)
    }

    @Test
    fun `switching data plans updates the quote and field validation together`() {
        val f = DemoTestFixture()
        val vm = own(f.viewModel())
        vm.accept(AxiaIntent.OpenService("Data"))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Recipient("08000000000")))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.DataPlan("20 GB")))
        val form = (vm.uiState.value.navigation.sheet as Sheet.Payment).form
        assertEquals("7000", form.input.amount)
        assertTrue(form.validation.valid)
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Review))
        vm.accept(AxiaIntent.ConfirmPayment)
        assertEquals(PaymentStatus.Successful, f.session.payments.first().status)
        assertEquals(12_145_000, f.session.balance)
    }

    @Test
    fun `offline drafts retain fields identity and selected SIM across reconnection and restoration`() {
        val f = DemoTestFixture()
        val nav = MemoryNavigationStore()
        val vm = own(f.viewModel(nav))
        f.connection.isOnline.value = false
        fillAirtime(vm)
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Sim(2)))
        val original = (vm.uiState.value.navigation.sheet as Sheet.Payment).form
        vm.accept(AxiaIntent.SaveDraft)
        f.connection.isOnline.value = true
        val restored = own(DemoTestFixture(f.store, f.connection).viewModel(nav))
        restored.accept(AxiaIntent.ResumeDraft)
        assertEquals(original, (restored.uiState.value.navigation.sheet as Sheet.Payment).form)
        assertEquals(12_845_000, f.session.balance)
    }

    @Test
    fun `restored sent SMS stays uncertain until explicit backend result and cannot submit again`() {
        val f = DemoTestFixture()
        val nav = MemoryNavigationStore()
        val vm = own(f.viewModel(nav))
        f.connection.isOnline.value = false
        fillAirtime(vm)
        vm.accept(AxiaIntent.ConfirmPayment)
        val sheet = vm.uiState.value.navigation.sheet as Sheet.Sms
        vm.accept(AxiaIntent.SendSms(sheet.requestId))
        val restored = own(DemoTestFixture(f.store, f.connection).viewModel(nav))
        f.connection.isOnline.value = true
        restored.accept(AxiaIntent.ConfirmPayment)
        assertEquals(12_845_000, restored.uiState.value.session.balance)
        assertEquals(sheet, restored.uiState.value.navigation.sheet)
        restored.accept(AxiaIntent.ReconcileSms(sheet.requestId))
        restored.accept(AxiaIntent.ReconcileSms(sheet.requestId))
        assertEquals(12_745_000, restored.uiState.value.session.balance)
        assertEquals(1, restored.uiState.value.session.payments.count { it.id == sheet.requestId })
    }

    @Test
    fun `funding another payment does not discard an unrelated saved draft`() {
        val f = DemoTestFixture()
        val vm = own(f.viewModel())
        f.connection.isOnline.value = false
        fillAirtime(vm)
        vm.accept(AxiaIntent.SaveDraft)
        val draft = vm.uiState.value.navigation.draft
        f.connection.isOnline.value = true
        vm.accept(AxiaIntent.OpenService("Add money"))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Amount("5000")))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Review))
        vm.accept(AxiaIntent.ConfirmPayment)
        assertEquals(draft, vm.uiState.value.navigation.draft)
        vm.accept(AxiaIntent.ResumeDraft)
        assertEquals(draft, (vm.uiState.value.navigation.sheet as Sheet.Payment).form)
    }

    @Test
    fun `live connection changes preserve open partial form and do not submit it`() {
        val f = DemoTestFixture()
        val vm = own(f.viewModel())
        vm.accept(AxiaIntent.OpenService("Airtime"))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Recipient("080123")))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Amount("2000")))
        val before = vm.uiState.value
        repeat(3) {
            f.connection.isOnline.value = false
            assertTrue(vm.uiState.value.session.offline)
            assertEquals(before.navigation, vm.uiState.value.navigation)
            f.connection.isOnline.value = true
            assertFalse(vm.uiState.value.session.offline)
            assertEquals(before.navigation, vm.uiState.value.navigation)
        }
        assertEquals(before.session.balance, f.session.balance)
        assertEquals(before.session.payments, f.session.payments)
        assertEquals(before.session.plans, f.session.plans)
    }

    @Test
    fun `startup uses current connection instead of a stale persisted mode`() {
        val f = DemoTestFixture(connection = FakeInternetConnection(false))
        val offline = own(f.viewModel())
        assertTrue(offline.uiState.value.session.offline)
        val restored = own(DemoTestFixture(f.store, FakeInternetConnection(true)).viewModel())
        assertFalse(restored.uiState.value.session.offline)
        assertEquals(
            offline.uiState.value.session.payments,
            restored.uiState.value.session.payments,
        )
    }

    @Test
    fun `clearing the ViewModel releases the connection subscription`() {
        val f = DemoTestFixture()
        own(f.viewModel())
        assertEquals(1, f.connection.isOnline.subscriptionCount.value)
        owners.forEach { it.clear() }
        assertEquals(0, f.connection.isOnline.subscriptionCount.value)
    }

    /** A preview wins over live changes; Automatic immediately resolves the latest real state. */
    @Test
    fun `manual overrides ignore network changes and automatic resumes the latest connection`() {
        val fixture = DemoTestFixture()
        val viewModel = own(fixture.viewModel())
        viewModel.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOffline))
        assertTrue(viewModel.uiState.value.session.offline)
        fixture.connection.isOnline.value = false
        fixture.connection.isOnline.value = true
        assertTrue(viewModel.uiState.value.session.offline)
        viewModel.accept(AxiaIntent.SetConnectionMode(ConnectionMode.Automatic))
        assertFalse(viewModel.uiState.value.session.offline)

        viewModel.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOnline))
        fixture.connection.isOnline.value = false
        assertFalse(viewModel.uiState.value.session.offline)
        viewModel.accept(AxiaIntent.SetConnectionMode(ConnectionMode.Automatic))
        assertTrue(viewModel.uiState.value.session.offline)
        fixture.connection.isOnline.value = true
        assertFalse(viewModel.uiState.value.session.offline)
    }

    /** Mode changes must not mutate, submit or discard a partial draft or uncertain SMS. */
    @Test
    fun `mode changes preserve drafts pending SMS identity and all financial state`() {
        val fixture = DemoTestFixture()
        val viewModel = own(fixture.viewModel())
        viewModel.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOffline))
        fillAirtime(viewModel)
        viewModel.accept(AxiaIntent.ConfirmPayment)
        val request = viewModel.uiState.value.navigation.sheet as Sheet.Sms
        viewModel.accept(AxiaIntent.SendSms(request.requestId))
        viewModel.accept(AxiaIntent.OpenService("Electricity"))
        viewModel.accept(AxiaIntent.EditPayment(PaymentIntent.Recipient("12345")))
        viewModel.accept(AxiaIntent.EditPayment(PaymentIntent.Amount("2000")))
        viewModel.accept(AxiaIntent.SaveDraft)
        val before = viewModel.uiState.value
        listOf(ConnectionMode.ForceOnline, ConnectionMode.Automatic, ConnectionMode.ForceOffline)
            .forEach { mode ->
                viewModel.accept(AxiaIntent.SetConnectionMode(mode))
                val after = viewModel.uiState.value
                assertEquals(before.navigation, after.navigation)
                assertEquals(before.session.balance, after.session.balance)
                assertEquals(before.session.payments, after.session.payments)
                assertEquals(before.session.plans, after.session.plans)
                assertEquals(before.session.smsOutbox, after.session.smsOutbox)
            }
        viewModel.accept(AxiaIntent.ResumeDraft)
        val form = (viewModel.uiState.value.navigation.sheet as Sheet.Payment).form
        assertEquals(before.navigation.draft, form)
    }

    /**
     * A restored session keeps its explicit preview even if the device reports a different mode.
     */
    @Test
    fun `manual preference survives restoration without resetting an open form`() {
        val fixture = DemoTestFixture()
        val navigation = MemoryNavigationStore()
        val viewModel = own(fixture.viewModel(navigation))
        viewModel.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOffline))
        fillAirtime(viewModel)
        val before = viewModel.uiState.value
        val restored =
            own(DemoTestFixture(fixture.store, FakeInternetConnection(true)).viewModel(navigation))
        assertEquals(before, restored.uiState.value)
        restored.accept(AxiaIntent.SetConnectionMode(ConnectionMode.Automatic))
        assertFalse(restored.uiState.value.session.offline)
        assertEquals(before.navigation, restored.uiState.value.navigation)
    }

    /** Failure to persist the preference leaves both the mode and financial snapshot unchanged. */
    @Test
    fun `failed preference save retains previous mode and can be retried`() {
        val fixture = DemoTestFixture()
        val viewModel = own(fixture.viewModel())
        val before = viewModel.uiState.value.session
        fixture.store.failSave = true
        viewModel.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOffline))
        assertEquals(before, viewModel.uiState.value.session)
        assertNotNull(viewModel.uiState.value.error)
        fixture.store.failSave = false
        viewModel.accept(AxiaIntent.DismissError)
        viewModel.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOffline))
        assertTrue(viewModel.uiState.value.session.offline)
        assertEquals(ConnectionMode.ForceOffline, fixture.session.connectionMode)
    }
}
