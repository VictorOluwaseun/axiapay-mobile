package com.kesofty.axiapay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.kesofty.axiapay.application.auth.ServerAccountAccess
import com.kesofty.axiapay.domain.auth.StoredAuthenticationSession
import com.kesofty.axiapay.presentation.auth.*
import com.kesofty.axiapay.wallet.application.*
import com.kesofty.axiapay.wallet.domain.*
import com.kesofty.axiapay.wallet.presentation.*
import java.math.BigInteger
import java.time.Instant
import java.util.Optional
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Exercise funding orchestration and visit ownership with controllable, non-spending ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class FundingViewModelTest {
    private val owners = ViewModelStore()

    /** Run reducers synchronously until a controlled network suspension. */
    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Cancel all owners before restoring the process dispatcher. */
    @After
    fun close() {
        owners.clear()
        Dispatchers.resetMain()
    }

    /** Register every test ViewModel for lifecycle cleanup. */
    private fun vm(
        fixture: FundingFixture,
        saved: SavedStateHandle = SavedStateHandle(),
        name: String = "funding",
    ): FundingViewModel =
        FundingViewModel(Optional.of(fixture.useCases), saved).also {
            owners.put(name, it)
            it.accept(FundingIntent.Bind(fixture.accountId, name))
        }

    /** Duplicate taps and app returns never submit again or imply credit. */
    @Test
    fun duplicateSubmissionAndOpayHandoffRemainPending() = runTest {
        val fixture = FundingFixture()
        val vm = vm(fixture)
        vm.accept(FundingIntent.Amount("100"))
        fixture.api.gate = CompletableDeferred()
        vm.accept(FundingIntent.Create)
        vm.accept(FundingIntent.Create)
        assertEquals(1, fixture.api.inputs.size)
        fixture.api.gate!!.complete(Unit)
        assertEquals(FundingStatus.AwaitingTransfer, vm.uiState.value.request?.status)
        vm.accept(FundingIntent.OpenOpay)
        assertEquals(FundingEffect.Kind.OpenOpay, vm.effects.first().kind)
        vm.accept(FundingIntent.Bind(fixture.accountId, "funding"))
        assertEquals(0, fixture.api.reconciles)
        vm.accept(FundingIntent.Reference("customer-reference"))
        vm.accept(FundingIntent.SubmitReference)
        assertEquals(FundingStatus.AwaitingConfirmation, vm.uiState.value.request?.status)
        assertEquals(1, fixture.api.inputs.size)
    }

    /**
     * Ambiguous creation retains the exact original key and terms despite attempted draft edits.
     */
    @Test
    fun uncertainCreationFreezesInputUntilExplicitRetry() = runTest {
        val fixture = FundingFixture()
        val vm = vm(fixture)
        fixture.api.createIssue = FundingIssue.Network
        vm.accept(FundingIntent.Option("hosted"))
        vm.accept(FundingIntent.Email("original@example.test"))
        vm.accept(FundingIntent.Amount("100.25"))
        vm.accept(FundingIntent.Create)
        assertTrue(vm.uiState.value.submissionUncertain)
        vm.accept(FundingIntent.Amount("200"))
        vm.accept(FundingIntent.Email("changed@example.test"))
        assertEquals("original@example.test", vm.uiState.value.email)
        fixture.api.createIssue = null
        vm.accept(FundingIntent.Create)
        assertEquals(fixture.api.inputs[0], fixture.api.inputs[1])
        assertEquals(BigInteger("10025"), fixture.api.inputs[0].amountMinorUnits)
    }

    /**
     * A definitive server validation rejection reopens the same draft and preserves its logical
     * key.
     */
    @Test
    fun rejectedEmailCanBeCorrectedAndResubmitted() = runTest {
        val fixture = FundingFixture()
        val model = vm(fixture)
        model.accept(FundingIntent.Option("hosted"))
        model.accept(FundingIntent.Amount("100"))
        model.accept(FundingIntent.Email("incorrect"))
        fixture.api.createIssue = FundingIssue.InvalidInput
        model.accept(FundingIntent.Create)
        assertEquals(FundingIssue.InvalidInput, model.uiState.value.issue)
        assertFalse(model.uiState.value.submissionUncertain)
        assertEquals("incorrect", model.uiState.value.email)
        model.accept(FundingIntent.Email("corrected@example.test"))
        fixture.api.createIssue = null
        model.accept(FundingIntent.Create)
        val (rejected, corrected) = fixture.api.inputs
        assertEquals(rejected.copy(payerEmail = "corrected@example.test"), corrected)
        assertNotNull(model.uiState.value.request)
    }

    /**
     * Cancelled work from an old visit cannot overwrite a new route, even if the network ignores
     * cancellation.
     */
    @Test
    fun lateCompletionCannotReplaceNewerVisitOrAccount() = runTest {
        val fixture = FundingFixture()
        val vm = vm(fixture)
        fixture.api.gate = CompletableDeferred()
        vm.accept(FundingIntent.Amount("100"))
        vm.accept(FundingIntent.Create)
        vm.accept(FundingIntent.Bind(null, null))
        fixture.api.gate!!.complete(Unit)
        assertNull(vm.uiState.value.accountId)
        assertNull(vm.uiState.value.request)
        assertFalse(vm.uiState.value.busy)
    }

    /**
     * Recreation restores server progress through GET and never repeats a
     * creation/reference/handoff.
     */
    @Test
    fun recreationReadsSavedRequestAndKeepsApprovedDistinctFromCredited() = runTest {
        val fixture = FundingFixture()
        val saved = SavedStateHandle()
        val first = vm(fixture, saved, "first")
        first.accept(FundingIntent.Amount("100"))
        first.accept(FundingIntent.Create)
        fixture.api.request = fixture.api.request.copy(status = FundingStatus.AwaitingCredit)
        val restoredHandle = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val restored = vm(fixture, restoredHandle, "restored")
        assertEquals(FundingStatus.AwaitingCredit, restored.uiState.value.request?.status)
        assertEquals(1, fixture.api.inputs.size)
        assertEquals(0, fixture.api.reconciles)
        fixture.api.request = fixture.api.request.copy(status = FundingStatus.Credited)
        restored.accept(FundingIntent.Refresh)
        assertEquals(FundingStatus.Credited, restored.uiState.value.request?.status)
        assertEquals(FundingEffect.Kind.RefreshWallet, restored.effects.first().kind)
    }

    /** A cold launch with no local handle recovers durable history without another creation. */
    @Test
    fun coldLaunchRecoversServerHistoryWithoutSubmittingAgain() = runTest {
        val fixture = FundingFixture()
        val first = vm(fixture, name = "first")
        first.accept(FundingIntent.Amount("100"))
        first.accept(FundingIntent.Create)
        first.accept(FundingIntent.Reference("saved-reference"))
        first.accept(FundingIntent.SubmitReference)
        owners.clear()
        val restored = vm(fixture, SavedStateHandle(), "cold")
        assertNull(restored.uiState.value.request)
        val saved = restored.uiState.value.context!!.requests.single()
        restored.accept(FundingIntent.Select(saved.id))
        assertEquals(FundingStatus.AwaitingConfirmation, restored.uiState.value.request?.status)
        assertEquals("saved-reference", restored.uiState.value.request?.customerReference)
        assertEquals(1, fixture.api.inputs.size)
        assertEquals(0, fixture.api.reconciles)
    }

    /**
     * A committed-credit effect refreshes the account's exact server snapshot, never a local
     * increment.
     */
    @Test
    fun committedCreditRefreshesTheAuthoritativeWalletDisplay() = runTest {
        val fixture = FundingFixture()
        val auth =
            AuthViewModel(
                SavedStateHandle(),
                fixture.accountAccess,
                fixture.clock,
                enabledRemoteConfiguration(),
            )
        owners.put("auth", auth)
        val funding = vm(fixture)
        val earlier = auth.uiState.value.homeWallet as HomeWalletState.Loaded
        val effects = mutableListOf<FundingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            funding.effects.collect { effect ->
                effects += effect
                if (effect.kind == FundingEffect.Kind.RefreshWallet)
                    auth.accept(AuthIntent.RetryWallet)
            }
        }
        funding.accept(FundingIntent.Amount("100"))
        funding.accept(FundingIntent.Create)
        fixture.api.request = fixture.api.request.copy(status = FundingStatus.AwaitingCredit)
        funding.accept(FundingIntent.Refresh)
        assertTrue(effects.isEmpty())
        assertEquals(earlier, auth.uiState.value.homeWallet)
        // Include unrelated ledger changes: the client must use the new total rather than adding
        // the deposit.
        fixture.wallets.wallet =
            fixture.wallets.wallet.copy(
                availableMinorUnits = BigInteger("78900"),
                totalMinorUnits = BigInteger("81400"),
            )
        val readsBeforeCredit = fixture.wallets.requests
        fixture.api.request = fixture.api.request.copy(status = FundingStatus.Credited)
        funding.accept(FundingIntent.Refresh)
        runCurrent()
        assertEquals(listOf(FundingEffect.Kind.RefreshWallet), effects.map { it.kind })
        assertEquals(readsBeforeCredit + 1, fixture.wallets.requests)
        assertEquals(HomeWalletState.Loaded(fixture.wallets.wallet), auth.uiState.value.homeWallet)
        assertEquals(AuthScreen.Dashboard, auth.uiState.value.screen)
        assertEquals(1, fixture.api.inputs.size)
    }

    /**
     * Exact decimal preparation rejects malformed and out-of-capability amounts without network
     * effects.
     */
    @Test
    fun preparationRejectsInvalidMoneyWithoutSending() {
        val fixture = FundingFixture()
        for (amount in listOf("0", "-1", "1.001", "1e3", "1000000000")) {
            try {
                fixture.useCases.prepareFundingRequest(fixture.api.option, amount, "", "key")
                fail("Invalid amount accepted")
            } catch (failure: FundingFailure) {
                assertEquals(FundingIssue.InvalidInput, failure.issue)
            }
        }
        assertTrue(fixture.api.inputs.isEmpty())
        val demo = FundingViewModel(Optional.empty(), SavedStateHandle())
        owners.put("demo", demo)
        assertFalse(demo.uiState.value.enabled)
    }
}

/** Share only neutral authentication/wallet fixtures; funding has no local balance authority. */
private class FundingFixture {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val authentication = RecordingAuthenticationRepository(now)
    val accountId = authentication.session.account.id
    private val sessions =
        MemoryAuthenticationSessionStore(
            StoredAuthenticationSession(
                authentication.session.accessToken,
                now.plusSeconds(3600),
                authentication.apiIdentity,
            ),
        )
    val wallets = RecordingWalletRemoteDataSource()
    val clock = MutableAuthenticationClock(now)
    private val dashboard = InMemoryDashboardRepository()
    private val walletRepository = dashboard.walletRepository(wallets)
    val accountAccess =
        ServerAccountAccess(authentication, sessions, clock, walletRepository, dashboard)
    val api = RecordingFundingRepository(wallets.wallet.id, wallets.apiIdentity)
    private val access = FundingSessionAccess(authentication, sessions, clock)
    val useCases =
        FundingUseCases(
            loadFundingContext = LoadFundingContext(access, walletRepository, api),
            prepareFundingRequest = PrepareFundingRequest(),
            createFundingRequest = CreateFundingRequest(access, api),
            getFundingRequest = GetFundingRequest(access, api),
            submitFundingReference = SubmitFundingReference(access, api),
            reconcileFundingRequest = ReconcileFundingRequest(access, api),
        )
}

/**
 * Controlled durable-server-shaped port supports uncertain and cancellation-resistant responses.
 */
private class RecordingFundingRepository(
    private val walletId: String,
    override val apiIdentity: String,
) : FundingRepository {
    val option =
        FundingOption(
            "vtpass-bank-transfer",
            "Bank transfer",
            "NGN",
            BigInteger("100"),
            BigInteger("100000000"),
            true,
        )
    var request =
        FundingRequest(
            "123e4567-e89b-42d3-a456-426614174004",
            walletId,
            option.id,
            BigInteger("10000"),
            "NGN",
            FundingStatus.AwaitingTransfer,
            FundingBankAccount("Fixture Bank", "Test collection", "0000000001"),
            null,
            null,
            null,
        )
    val inputs = mutableListOf<FundingInput>()
    var gate: CompletableDeferred<Unit>? = null
    var createIssue: FundingIssue? = null
    var reconciles = 0

    /** Return saved requests without a write. */
    override suspend fun context(token: String, walletId: String, offset: Int) =
        FundingContext(
            walletId,
            listOf(option, option.copy(id = "hosted", bankTransfer = false)),
            if (inputs.isEmpty()) emptyList() else listOf(request),
            null,
        )

    /** Capture submission before optionally losing its acknowledgement. */
    override suspend fun create(
        token: String,
        walletId: String,
        input: FundingInput,
    ): FundingRequest {
        inputs += input
        gate?.let { withContext(NonCancellable) { it.await() } }
        createIssue?.let { throw FundingFailure(it) }
        return request.copy(amountMinorUnits = input.amountMinorUnits)
    }

    /** Read only. */
    override suspend fun get(token: String, requestId: String) = request

    /** Customer evidence transitions only into human review. */
    override suspend fun submitReference(
        token: String,
        requestId: String,
        reference: String,
    ): FundingRequest {
        request =
            request.copy(status = FundingStatus.AwaitingConfirmation, customerReference = reference)
        return request
    }

    /** Explicit polling returns the controlled authoritative status. */
    override suspend fun reconcile(token: String, requestId: String): FundingRequest {
        reconciles++
        return request
    }
}
