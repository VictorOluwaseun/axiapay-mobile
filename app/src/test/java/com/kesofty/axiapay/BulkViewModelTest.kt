package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.domain.BulkService
import com.kesofty.axiapay.domain.ConnectionMode
import com.kesofty.axiapay.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Verifies bulk intent ownership, draft restoration and navigation during suspended execution. */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkViewModelTest {
    private val owners = mutableListOf<ViewModelStore>()

    /** Releases each execution scope and restores the real main dispatcher. */
    @After
    fun cleanup() {
        owners.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    /** Gives each test ViewModel an explicit lifecycle owner. */
    private fun own(vm: AxiaViewModel): AxiaViewModel {
        ViewModelStore().also {
            it.put("bulk", vm)
            owners += it
        }
        return vm
    }

    /** Prepares review through exactly the same intents emitted by Compose. */
    private fun review(vm: AxiaViewModel) {
        vm.accept(AxiaIntent.Bulk(BulkIntent.Open))
        vm.accept(AxiaIntent.Bulk(BulkIntent.ChooseService(BulkService.Airtime)))
        vm.accept(AxiaIntent.Bulk(BulkIntent.LoadSamples))
        vm.accept(AxiaIntent.Bulk(BulkIntent.Review))
    }

    /** Closing and reopening an unsubmitted draft retains its logical identity and terms. */
    @Test
    fun draftAndEditorRestoreWithoutMovingMoney() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = DemoTestFixture()
        val navigation = MemoryNavigationStore()
        val vm = own(f.viewModel(navigation))
        review(vm)
        val draft = vm.uiState.value.navigation.bulk.draft!!
        vm.accept(AxiaIntent.Bulk(BulkIntent.EditRecipient(draft.recipients[2].id)))
        val editor = vm.uiState.value.navigation.bulk.editor!!
        vm.accept(AxiaIntent.Bulk(BulkIntent.RecipientValue(editor.copy(name = "My home meter"))))
        vm.accept(AxiaIntent.Bulk(BulkIntent.Close))
        vm.accept(AxiaIntent.Bulk(BulkIntent.Open))
        vm.accept(AxiaIntent.Bulk(BulkIntent.ChooseService(BulkService.Airtime)))
        assertEquals(BulkScreen.Recipients, vm.uiState.value.navigation.bulk.screen)
        val restored = own(f.viewModel(navigation))
        runCurrent()
        assertEquals("My home meter", restored.uiState.value.navigation.bulk.editor!!.name)
        restored.accept(AxiaIntent.Bulk(BulkIntent.Review))
        restored.accept(AxiaIntent.Bulk(BulkIntent.Close))
        restored.accept(AxiaIntent.Bulk(BulkIntent.Open))
        assertEquals(draft.id, restored.uiState.value.navigation.bulk.draft!!.id)
        assertEquals(
            "My home meter",
            restored.uiState.value.navigation.bulk.draft!!.recipients[2].name,
        )
        assertEquals(12_845_000L, f.session.balance)
    }

    /**
     * Double taps cannot enqueue twice and a late result cannot replace newer navigation/drafts.
     */
    @Test
    fun duplicateSubmissionAndLateCompletionPreserveNewDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = DemoTestFixture()
        val vm = own(f.viewModel())
        review(vm)
        val id = vm.uiState.value.navigation.bulk.draft!!.id
        vm.accept(AxiaIntent.Bulk(BulkIntent.Submit))
        vm.accept(AxiaIntent.Bulk(BulkIntent.Submit))
        runCurrent()
        vm.accept(AxiaIntent.Bulk(BulkIntent.Close))
        vm.accept(AxiaIntent.Bulk(BulkIntent.Open))
        vm.accept(AxiaIntent.Bulk(BulkIntent.ChooseService(BulkService.Data)))
        advanceUntilIdle()
        assertEquals(1, f.session.batches.count { it.id == id })
        assertEquals(BulkScreen.Recipients, vm.uiState.value.navigation.bulk.screen)
        assertEquals(BulkService.Data, vm.uiState.value.navigation.bulk.draft!!.service)
        assertNotEquals(id, vm.uiState.value.navigation.bulk.draft!!.id)
        assertEquals(3, f.session.batches.first().successfulCount)
    }

    /**
     * Moving a saved individual form consumes its old alias and never reserves funds during
     * editing.
     */
    @Test
    fun expandingIndividualFormConsumesSingleDraftAndCannotSubmitTwice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = DemoTestFixture()
        val vm = own(f.viewModel())
        vm.accept(AxiaIntent.OpenService("Airtime"))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Recipient("08030000001")))
        vm.accept(AxiaIntent.EditPayment(PaymentIntent.Amount("1500")))
        val requestId = (vm.uiState.value.navigation.sheet as Sheet.Payment).form.requestId
        vm.accept(AxiaIntent.SaveDraft)
        vm.accept(AxiaIntent.ResumeDraft)
        vm.accept(AxiaIntent.Bulk(BulkIntent.FromPayment))
        vm.accept(AxiaIntent.Bulk(BulkIntent.FromPayment))
        assertNull(vm.uiState.value.navigation.sheet)
        assertNull(vm.uiState.value.navigation.draft)
        assertEquals(
            listOf(requestId),
            vm.uiState.value.navigation.bulk.draft!!.recipients.map { it.id },
        )
        assertEquals(12_845_000L, f.session.balance)
        vm.accept(AxiaIntent.Bulk(BulkIntent.Review))
        vm.accept(AxiaIntent.Bulk(BulkIntent.SimulateFailure(false)))
        vm.accept(AxiaIntent.Bulk(BulkIntent.Submit))
        advanceUntilIdle()
        assertEquals(12_695_000L, f.session.balance)
        assertEquals(1, f.session.payments.count { it.id.endsWith(":$requestId") })
    }

    /** Going offline pauses execution and reconnection alone does not resume or debit again. */
    @Test
    fun offlineRunRequiresExplicitResume() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = DemoTestFixture()
        val vm = own(f.viewModel())
        review(vm)
        vm.accept(AxiaIntent.Bulk(BulkIntent.Submit))
        runCurrent()
        vm.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOffline))
        advanceUntilIdle()
        val pending = f.session.batches.first()
        assertTrue(pending.hasPending)
        val balance = f.session.balance
        vm.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOnline))
        advanceUntilIdle()
        assertEquals(pending, f.session.batches.first())
        assertEquals(balance, f.session.balance)
        vm.accept(AxiaIntent.DismissError)
        vm.accept(AxiaIntent.Bulk(BulkIntent.Resume))
        advanceUntilIdle()
        assertFalse(f.session.batches.first().hasPending)
        assertEquals(3, f.session.batches.first().successfulCount)
    }
}
