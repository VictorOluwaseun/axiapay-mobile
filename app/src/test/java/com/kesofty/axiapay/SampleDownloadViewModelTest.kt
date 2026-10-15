package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Exercises the file workflow's ownership, one-time callbacks, failure and restoration rules. */
@OptIn(ExperimentalCoroutinesApi::class)
class SampleDownloadViewModelTest {
    private val owners = mutableListOf<ViewModelStore>()

    /** Releases owned jobs before restoring the global test dispatcher. */
    @After
    fun cleanup() {
        owners.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    /** Exposes a controlled save operation without Android or financial side effects. */
    private fun repository(save: suspend (RecipientSample, String) -> SampleExportResult) =
        object : RecipientSampleRepository {
            override val availableFormats =
                listOf(RecipientSampleFormat.Csv, RecipientSampleFormat.Excel)

            /**
             * Delegates writes so tests can suspend, fail or observe the exact requested sample.
             */
            override suspend fun save(sample: RecipientSample, destination: String) =
                save.invoke(sample, destination)
        }

    /** Opens a real payment owner on the same route that the demo controls use. */
    private fun open(
        fixture: DemoTestFixture,
        repository: RecipientSampleRepository,
        navigation: NavigationStore = MemoryNavigationStore(),
    ): AxiaViewModel {
        val vm = fixture.viewModel(navigation, sampleRepository = repository)
        ViewModelStore().also {
            it.put("samples", vm)
            owners += it
        }
        vm.accept(AxiaIntent.Bulk(BulkIntent.ChooseService(BulkService.Airtime)))
        vm.accept(AxiaIntent.Bulk(BulkIntent.OpenImport))
        return vm
    }

    /**
     * Dispatches only sample intents, keeping each test's assertions about payment state explicit.
     */
    private fun send(vm: AxiaViewModel, intent: SampleDownloadIntent) =
        vm.accept(AxiaIntent.Bulk(BulkIntent.SampleDownload(intent)))

    /** Starts and acknowledges the one-time UI effect without invoking Android in a JVM test. */
    private fun request(vm: AxiaViewModel): String {
        send(vm, SampleDownloadIntent.Request(RecipientSampleFormat.Csv))
        val id = vm.uiState.value.navigation.bulk.importState!!.sampleDownload!!.id
        send(vm, SampleDownloadIntent.PickerOpened(id))
        return id
    }

    /**
     * Duplicate picker results and overlapping requests write once and leave drafts/wallet alone.
     */
    @Test
    fun duplicateCallbacksPreserveDraftAndWallet() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = DemoTestFixture()
        var writes = 0
        val vm =
            open(
                fixture,
                repository { sample, destination ->
                    writes++
                    assertEquals(BulkService.Airtime, sample.service)
                    assertEquals("content://samples/new", destination)
                    delay(100)
                    SampleExportResult.Saved
                },
            )
        vm.accept(AxiaIntent.Bulk(BulkIntent.Back))
        val unfinished =
            vm.uiState.value.navigation.bulk.editor!!.copy(
                name = "Unfinished entry",
                recipientReference = "080",
                amount = "25",
            )
        vm.accept(AxiaIntent.Bulk(BulkIntent.RecipientValue(unfinished)))
        vm.accept(AxiaIntent.Bulk(BulkIntent.OpenImport))
        val initial = vm.uiState.value
        val id = request(vm)
        send(vm, SampleDownloadIntent.Request(RecipientSampleFormat.Excel))
        send(vm, SampleDownloadIntent.Destination(id, "content://samples/new"))
        send(vm, SampleDownloadIntent.Destination(id, "content://samples/duplicate"))
        advanceUntilIdle()
        assertEquals(1, writes)
        assertEquals(
            SampleDownloadPhase.Saved,
            vm.uiState.value.navigation.bulk.importState!!.sampleDownload!!.phase,
        )
        assertEquals(initial.navigation.bulk.draft, vm.uiState.value.navigation.bulk.draft)
        assertEquals(initial.navigation.bulk.editor, vm.uiState.value.navigation.bulk.editor)
        assertEquals(initial.session, vm.uiState.value.session)
    }

    /**
     * Picker cancellation is silent; a failed save retains its cause and allows a fresh attempt.
     */
    @Test
    fun cancellationAndFailureRemainRecoverable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var writes = 0
        val failure = java.io.IOException("test provider failure")
        val vm =
            open(
                DemoTestFixture(),
                repository { _, _ ->
                    writes++
                    SampleExportResult.Failed(failure)
                },
            )
        val cancelled = request(vm)
        send(vm, SampleDownloadIntent.Destination(cancelled, null))
        assertNull(vm.uiState.value.navigation.bulk.importState!!.sampleDownload)
        val id = request(vm)
        send(vm, SampleDownloadIntent.Destination(cancelled, "content://samples/stale"))
        send(vm, SampleDownloadIntent.Destination(id, "content://samples/failure"))
        advanceUntilIdle()
        assertEquals(1, writes)
        assertSame(
            failure,
            vm.uiState.value.navigation.bulk.importState!!.sampleDownload!!.failure!!.cause,
        )
        val retryId = request(vm)
        assertNotEquals(id, retryId)
        send(vm, SampleDownloadIntent.PickerUnavailable(retryId))
        assertEquals(
            SampleDownloadPhase.Failed,
            vm.uiState.value.navigation.bulk.importState!!.sampleDownload!!.phase,
        )
    }

    /** A late noncancellable provider cannot reopen the previous service after navigation. */
    @Test
    fun lateCompletionAndOldPickerResultCannotReplaceNewSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var completed = false
        val vm =
            open(
                DemoTestFixture(),
                repository { _, _ ->
                    withContext(NonCancellable) {
                        delay(100)
                        completed = true
                    }
                    SampleExportResult.Saved
                },
            )
        val id = request(vm)
        send(vm, SampleDownloadIntent.Destination(id, "content://samples/late"))
        runCurrent()
        vm.accept(AxiaIntent.Bulk(BulkIntent.Close))
        vm.accept(AxiaIntent.Bulk(BulkIntent.ChooseService(BulkService.TV)))
        vm.accept(AxiaIntent.Bulk(BulkIntent.OpenImport))
        send(vm, SampleDownloadIntent.Destination(id, "content://samples/stale"))
        advanceUntilIdle()
        assertTrue(completed)
        assertEquals(BulkService.TV, vm.uiState.value.navigation.bulk.draft!!.service)
        assertNull(vm.uiState.value.navigation.bulk.importState!!.sampleDownload)
    }

    /** Neither unavailable flavors nor restored transient state can start a file write. */
    @Test
    fun capabilityAndRestorationPreventUnexpectedWrites() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = DemoTestFixture()
        val unavailable = fixture.viewModel()
        ViewModelStore().also {
            it.put("disabled", unavailable)
            owners += it
        }
        unavailable.accept(AxiaIntent.Bulk(BulkIntent.ChooseService(BulkService.Data)))
        unavailable.accept(AxiaIntent.Bulk(BulkIntent.OpenImport))
        send(unavailable, SampleDownloadIntent.Request(RecipientSampleFormat.Csv))
        assertTrue(unavailable.uiState.value.navigation.bulk.importState!!.sampleFormats.isEmpty())
        assertNull(unavailable.uiState.value.navigation.bulk.importState!!.sampleDownload)
        val navigation = MemoryNavigationStore()
        val repository = repository { _, _ -> error("Must not replay") }
        val vm = open(fixture, repository, navigation)
        val id = request(vm)
        val restored = fixture.viewModel(navigation, sampleRepository = repository)
        ViewModelStore().also {
            it.put("restored", restored)
            owners += it
        }
        send(restored, SampleDownloadIntent.Destination(id, "content://samples/replay"))
        assertEquals(BulkScreen.Recipients, restored.uiState.value.navigation.bulk.screen)
        assertNull(restored.uiState.value.navigation.bulk.importState)
        advanceUntilIdle()
    }

    /** Cancellation from an adapter is propagated by the job without leaving a busy UI stuck. */
    @Test
    fun cancelledWriteIsNotReportedSaved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm =
            open(
                DemoTestFixture(),
                repository { _, _ -> throw CancellationException("test cancellation") },
            )
        send(vm, SampleDownloadIntent.Destination(request(vm), "content://samples/cancelled"))
        advanceUntilIdle()
        assertEquals(
            SampleDownloadPhase.Failed,
            vm.uiState.value.navigation.bulk.importState!!.sampleDownload!!.phase,
        )
        assertFalse(vm.uiState.value.navigation.bulk.importState!!.sampleDownload!!.busy)
    }
}
