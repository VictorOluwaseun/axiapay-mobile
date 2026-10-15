package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Verifies local import cannot replay payments or overwrite newer navigation and unfinished input.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkImportViewModelTest {
    private val owners = mutableListOf<ViewModelStore>()
    private val document =
        ImportDocument(
            "list.csv",
            null,
            listOf(
                ImportTableRow(1, listOf("recipient", "provider", "amount").map(::ImportCell)),
                ImportTableRow(2, listOf("08030000001", "MTN", "1500.25").map(::ImportCell)),
            ),
        )

    /** Releases import scopes and the overridden main dispatcher after every test. */
    @After
    fun cleanup() {
        owners.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    /** Uses a lifecycle-owned real ViewModel and the normal service/import intents. */
    private fun open(
        fixture: DemoTestFixture,
        repository: RecipientImportRepository,
        navigation: NavigationStore = MemoryNavigationStore(),
    ): AxiaViewModel {
        val vm = fixture.viewModel(navigation, importRepository = repository)
        ViewModelStore().also {
            it.put("import", vm)
            owners += it
        }
        vm.accept(AxiaIntent.Bulk(BulkIntent.Open))
        vm.accept(AxiaIntent.Bulk(BulkIntent.ChooseService(BulkService.Airtime)))
        return vm
    }

    /** Starts a specific picker session exactly once, as the UI callback would. */
    private fun read(vm: AxiaViewModel): String {
        vm.accept(AxiaIntent.Bulk(BulkIntent.OpenImport))
        val id = vm.uiState.value.navigation.bulk.importState!!.id
        vm.accept(AxiaIntent.Bulk(BulkIntent.ImportFile(id, "content://test/list.csv")))
        return id
    }

    /** Double add actions append once and preserve a partially entered manual recipient. */
    @Test
    fun explicitMergePreservesEditorAndDoesNotSpend() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = DemoTestFixture()
        val vm = open(f, RecipientImportRepository { ImportDocumentResult.Success(document) })
        val editor =
            vm.uiState.value.navigation.bulk.editor!!.copy(
                recipientReference = "080",
                amount = "22",
            )
        vm.accept(AxiaIntent.Bulk(BulkIntent.RecipientValue(editor)))
        val before = f.session
        read(vm)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.navigation.bulk.draft!!.recipients.isEmpty())
        vm.accept(AxiaIntent.Bulk(BulkIntent.AddImportedRecipients))
        vm.accept(AxiaIntent.Bulk(BulkIntent.AddImportedRecipients))
        assertEquals(1, vm.uiState.value.navigation.bulk.draft!!.recipients.size)
        assertEquals(editor, vm.uiState.value.navigation.bulk.editor)
        assertEquals(before, f.session)
    }

    /**
     * Leaving a suspended import cancels it; even a noncancellable late result cannot reopen it.
     */
    @Test
    fun cancellationAndLateCompletionPreserveAnotherService() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var finished = false
        val vm =
            open(
                DemoTestFixture(),
                RecipientImportRepository {
                    withContext(NonCancellable) {
                        delay(100)
                        finished = true
                    }
                    ImportDocumentResult.Success(document)
                },
            )
        val oldId = read(vm)
        runCurrent()
        vm.accept(AxiaIntent.Bulk(BulkIntent.Close))
        vm.accept(AxiaIntent.Bulk(BulkIntent.ChooseService(BulkService.Data)))
        advanceUntilIdle()
        vm.accept(AxiaIntent.Bulk(BulkIntent.ImportFile(oldId, "content://test/late.csv")))
        assertTrue(finished)
        assertEquals(BulkService.Data, vm.uiState.value.navigation.bulk.draft!!.service)
        assertEquals(BulkScreen.Recipients, vm.uiState.value.navigation.bulk.screen)
        assertNull(vm.uiState.value.navigation.bulk.importState)
    }

    /** Duplicate file callbacks cannot overlap reads, and failures leave the draft available. */
    @Test
    fun duplicateReadAndFailureAreRecoverable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var count = 0
        val vm =
            open(
                DemoTestFixture(),
                RecipientImportRepository {
                    count++
                    delay(100)
                    ImportDocumentResult.Failure(ImportFileIssue.InvalidFile)
                },
            )
        val id = read(vm)
        vm.accept(AxiaIntent.Bulk(BulkIntent.ImportFile(id, "content://test/list.csv")))
        advanceUntilIdle()
        assertEquals(1, count)
        assertEquals(
            ImportFileIssue.InvalidFile,
            vm.uiState.value.navigation.bulk.importState!!.failure,
        )
        assertFalse(vm.uiState.value.navigation.bulk.importState!!.loading)
        vm.accept(AxiaIntent.Bulk(BulkIntent.Back))
        assertEquals(BulkScreen.Recipients, vm.uiState.value.navigation.bulk.screen)
        assertNotNull(vm.uiState.value.navigation.bulk.editor)
    }

    /** A restored owner retains drafts but never reopens a file grant or repeats a read. */
    @Test
    fun restorationDiscardsTransientImportOnly() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = DemoTestFixture()
        val navigation = MemoryNavigationStore()
        var reads = 0
        val repository = RecipientImportRepository {
            reads++
            ImportDocumentResult.Success(document)
        }
        val vm = open(f, repository, navigation)
        read(vm)
        advanceUntilIdle()
        val restored = f.viewModel(navigation, importRepository = repository)
        ViewModelStore().also {
            it.put("restored", restored)
            owners += it
        }
        runCurrent()
        assertEquals(1, reads)
        assertEquals(BulkScreen.Recipients, restored.uiState.value.navigation.bulk.screen)
        assertEquals(
            vm.uiState.value.navigation.bulk.draft,
            restored.uiState.value.navigation.bulk.draft,
        )
        assertNull(restored.uiState.value.navigation.bulk.importState)
    }
}
