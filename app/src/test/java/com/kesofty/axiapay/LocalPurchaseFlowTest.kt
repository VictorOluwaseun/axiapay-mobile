package com.kesofty.axiapay

import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.domain.config.PaymentFeatures
import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.presentation.purchases.*
import java.util.Optional
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/**
 * Local-first input and late-refresh regressions exercise the actual purchase owner without
 * spending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalPurchaseFlowTest {
    private val owners = ViewModelStore()

    /** Run UI intents eagerly while controlled network gates stay suspended. */
    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Cancel every test-owned refresh and payment timer. */
    @After
    fun close() {
        owners.clear()
        Dispatchers.resetMain()
    }

    /** Construct the same purchase reducer with explicitly enabled feature policy. */
    private fun vm(f: PurchaseFixture): PurchaseViewModel =
        PurchaseViewModel(Optional.of(f.useCases), f.clock).also {
            it.accept(PurchaseIntent.UpdateFeatures(PaymentFeatures.AllEnabled))
            owners.put("purchases", it)
        }

    /** Seed actual scoped display data separately from a network refresh. */
    private suspend fun seed(f: PurchaseFixture) {
        f.dashboard.saveAccount(f.api.apiIdentity, f.authentication.session.account)
        f.dashboard.saveWallet(f.api.apiIdentity, f.accountId, f.wallets.wallet)
        f.catalogue.seed(
            PurchaseOwner(f.accountId, f.api.apiIdentity),
            PurchaseService.Airtime,
            listOf(f.api.product),
        )
    }

    /** Phone and amount input are immediately editable even before the first catalogue download. */
    @Test
    fun firstUseDoesNotBlockTypingOnTheNetwork() = runTest {
        val f = PurchaseFixture()
        f.api.productsGate = CompletableDeferred()
        val vm = vm(f)
        vm.accept(PurchaseIntent.Bind(f.accountId, "first"))
        assertEquals(PurchaseScreen.Form, vm.uiState.value.screen)
        assertTrue(vm.uiState.value.isRefreshing)
        assertFalse(vm.uiState.value.isProcessing)
        vm.accept(PurchaseIntent.EditRecipient("08012345678"))
        vm.accept(PurchaseIntent.EditAmount("2000"))
        assertEquals("08012345678", vm.uiState.value.recipient)
        assertEquals("2000", vm.uiState.value.amount)
        assertTrue(vm.uiState.value.providers.isEmpty())
        f.api.productsGate!!.complete(Unit)
        assertEquals(listOf("mtn"), vm.uiState.value.providers)
        assertEquals("2000", vm.uiState.value.amount)
        assertEquals("08012345678", vm.uiState.value.recipient)
        assertFalse(vm.uiState.value.isRefreshing)
        assertEquals(0, f.api.quoteCalls)
    }

    /**
     * Cached products are selectable during refresh; changed availability keeps the typed draft.
     */
    @Test
    fun cachedNetworksRemainUsableAndChangedTermsPreserveDrafts() = runTest {
        val f = PurchaseFixture()
        seed(f)
        f.api.catalogue = listOf(f.api.product.copy(isSubmissionAvailable = false))
        f.api.productsGate = CompletableDeferred()
        val vm = vm(f)
        vm.accept(PurchaseIntent.Bind(f.accountId, "cached"))
        vm.accept(PurchaseIntent.SelectProduct(f.api.product.id))
        vm.accept(PurchaseIntent.EditRecipient("08012345678"))
        vm.accept(PurchaseIntent.EditAmount("100"))
        assertNotNull(vm.uiState.value.prepared)
        f.api.productsGate!!.complete(Unit)
        assertEquals(f.api.product.id, vm.uiState.value.selectedProductId)
        assertEquals("100", vm.uiState.value.amount)
        assertEquals("08012345678", vm.uiState.value.recipient)
        assertFalse(vm.uiState.value.selectedProduct!!.isSubmissionAvailable)
        assertEquals(0, f.api.quoteCalls)
        assertTrue(f.api.submissions.isEmpty())
    }

    /** Withdrawing a product drops only its selection and never silently chooses a replacement. */
    @Test
    fun withdrawnProductRequiresAnExplicitNewChoice() = runTest {
        val f = PurchaseFixture()
        seed(f)
        f.api.catalogue = emptyList()
        f.api.productsGate = CompletableDeferred()
        val vm = vm(f)
        vm.accept(PurchaseIntent.Bind(f.accountId, "withdrawal"))
        vm.accept(PurchaseIntent.SelectProduct(f.api.product.id))
        vm.accept(PurchaseIntent.EditRecipient("08012345678"))
        vm.accept(PurchaseIntent.EditAmount("100"))
        f.api.productsGate!!.complete(Unit)
        assertNull(vm.uiState.value.selectedProductId)
        assertNull(vm.uiState.value.prepared)
        assertEquals("100", vm.uiState.value.amount)
        assertEquals(PurchaseIssue.ProductUnavailable, vm.uiState.value.issue)
    }

    /** Losing connectivity preserves the last complete catalogue and permits another local edit. */
    @Test
    fun refreshFailureKeepsCachedOptionsAndDrafts() = runTest {
        val f = PurchaseFixture()
        seed(f)
        f.api.catalogueFailure = PurchaseIssue.Network
        val vm = vm(f)
        vm.accept(PurchaseIntent.Bind(f.accountId, "offline"))
        assertEquals(PurchaseScreen.Form, vm.uiState.value.screen)
        assertEquals(listOf("mtn"), vm.uiState.value.providers)
        assertEquals(PurchaseIssue.Network, vm.uiState.value.refreshIssue)
        vm.accept(PurchaseIntent.EditAmount("5000"))
        assertEquals("5000", vm.uiState.value.amount)
        assertTrue(vm.uiState.value.context!!.isCached)
    }

    /** A delayed refresh cannot replace the explicit review or trigger a quote on its own. */
    @Test
    fun lateRefreshCannotOverwriteReviewOrANewerVisit() = runTest {
        val f = PurchaseFixture()
        seed(f)
        f.api.productsGate = CompletableDeferred()
        val vm = vm(f)
        vm.accept(PurchaseIntent.Bind(f.accountId, "review"))
        vm.accept(PurchaseIntent.SelectProduct(f.api.product.id))
        vm.accept(PurchaseIntent.EditRecipient("08012345678"))
        vm.accept(PurchaseIntent.EditAmount("100"))
        vm.accept(PurchaseIntent.Review)
        assertEquals(PurchaseScreen.Review, vm.uiState.value.screen)
        val reviewed = vm.uiState.value.review
        vm.accept(PurchaseIntent.Reload)
        assertEquals(1, f.api.productsCalls)
        f.api.productsGate!!.complete(Unit)
        assertEquals(reviewed, vm.uiState.value.review)
        assertEquals(PurchaseScreen.Review, vm.uiState.value.screen)
        assertEquals(1, f.api.quoteCalls)
        vm.accept(PurchaseIntent.Bind(null, null))
        assertNull(vm.uiState.value.context)
        assertTrue(f.api.submissions.isEmpty())
    }

    /**
     * Opening a new draft from locally recovered history retains the refresh and cannot reopen
     * history on completion.
     */
    @Test
    fun newDraftWhileRefreshingKeepsItsOwnScreen() = runTest {
        val f = PurchaseFixture()
        seed(f)
        f.store.records =
            listOf(
                PurchaseRequest(
                    PurchaseOwner(f.accountId, f.api.apiIdentity),
                    "saved-key",
                    "saved-quote",
                    f.now,
                ),
            )
        f.api.productsGate = CompletableDeferred()
        val vm = vm(f)
        vm.accept(PurchaseIntent.Bind(f.accountId, "recovered"))
        assertEquals(PurchaseScreen.History, vm.uiState.value.screen)
        vm.accept(PurchaseIntent.NewPurchase)
        assertEquals(PurchaseScreen.Form, vm.uiState.value.screen)
        assertTrue(vm.uiState.value.isRefreshing)
        vm.accept(PurchaseIntent.EditRecipient("08012345678"))
        vm.accept(PurchaseIntent.Reload)
        assertEquals(1, f.api.productsCalls)
        f.api.productsGate!!.complete(Unit)
        assertEquals(PurchaseScreen.Form, vm.uiState.value.screen)
        assertEquals("08012345678", vm.uiState.value.recipient)
        assertFalse(vm.uiState.value.isRefreshing)
        assertEquals(0, f.api.quoteCalls)
    }

    /** A cache hit cannot cross the API/account identity recorded by the protected session. */
    @Test
    fun anotherAccountCannotReadTheCachedCatalogue() = runTest {
        val f = PurchaseFixture()
        seed(f)
        val vm = vm(f)
        vm.accept(PurchaseIntent.Bind("another-account", "different-owner"))
        assertEquals(PurchaseScreen.SignIn, vm.uiState.value.screen)
        assertNull(vm.uiState.value.context)
        assertEquals(0, f.api.productsCalls)
    }
}
