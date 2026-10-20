package com.kesofty.axiapay

import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.auth.AccountAccessMode
import com.kesofty.axiapay.domain.auth.AccountContact
import com.kesofty.axiapay.domain.auth.AccountIdentity
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.auth.AuthScreen
import com.kesofty.axiapay.presentation.auth.AuthUiState
import com.kesofty.axiapay.presentation.navigation.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Navigation histories preserve existing reducer Back contracts without executing workflows. */
class AppNavigationTest {
    /** Verification always returns to its own signup/login form, never another contact flow. */
    @Test
    fun proofReturnsToItsOwningForm() {
        for (creating in listOf(false, true)) {
            val stack =
                accountBackStack(
                    AuthUiState(screen = AuthScreen.Verification, isCreatingAccount = creating),
                )
            assertEquals(
                listOf(
                    AppDestination.Welcome,
                    if (creating) AppDestination.CreateAccount else AppDestination.SignIn,
                    AppDestination.Verification,
                ),
                stack,
            )
        }
    }

    /** Login and explicit preview entry discard account-entry history before opening Home. */
    @Test
    fun dashboardCannotPopBackToAnAuthenticationForm() {
        assertEquals(
            listOf(AppDestination.Dashboard),
            accountBackStack(AuthUiState(screen = AuthScreen.Dashboard)),
        )
        assertEquals(
            listOf(AppDestination.Welcome),
            accountBackStack(AuthUiState()),
        )
    }

    /** Real account details return to the retained dashboard; demo completion returns Welcome. */
    @Test
    fun accountDetailsAndDemoCompletionKeepTheirExistingParents() {
        val real =
            AuthUiState(
                screen = AuthScreen.Complete,
                mode = AccountAccessMode.Server,
                account =
                    AccountIdentity(
                        "account-fixture",
                        AccountContact(AuthContactType.Email, "fixture@example.test"),
                        Instant.EPOCH,
                    ),
            )
        assertEquals(
            listOf(AppDestination.Dashboard, AppDestination.AccountOverview),
            accountBackStack(real),
        )
        assertEquals(
            listOf(AppDestination.Welcome, AppDestination.AccountOverview),
            accountBackStack(real.copy(mode = AccountAccessMode.Demo)),
        )
    }

    /** Recovery and startup remain cancellable without creating an authenticated destination. */
    @Test
    fun recoveryDoesNotRestoreAProtectedScreenFromControllerHistory() {
        for (screen in listOf(AuthScreen.Restoring, AuthScreen.SessionRecovery)) {
            val stack = accountBackStack(AuthUiState(screen = screen))
            assertEquals(AppDestination.Welcome, stack.first())
            assertFalse(AppDestination.Dashboard in stack)
            assertEquals(2, stack.size)
        }
    }

    /** Tab changes retain the established single Back to Home rather than a tab-click history. */
    @Test
    fun everyTabReturnsDirectlyHome() {
        val destinations =
            listOf(
                AppDestination.Home,
                AppDestination.Payments,
                AppDestination.Activity,
                AppDestination.Plans,
            )
        for ((tab, destination) in destinations.withIndex()) {
            assertEquals(
                if (tab == 0) listOf(destination) else listOf(AppDestination.Home, destination),
                dashboardBackStack(NavigationState(tab = tab)),
            )
        }
    }

    /** Import and editable review return through the same recipients and service picker. */
    @Test
    fun editableBulkFlowsRetainTheirOwningTabAndDraftHistory() {
        for ((screen, destination) in
            listOf(
                BulkScreen.Import to AppDestination.BulkImport,
                BulkScreen.Review to AppDestination.BulkReview,
            )) {
            assertEquals(
                listOf(
                    AppDestination.Home,
                    AppDestination.Payments,
                    AppDestination.BulkServices,
                    AppDestination.BulkRecipients,
                    destination,
                ),
                dashboardBackStack(NavigationState(tab = 1, bulk = BulkFlowState(screen = screen))),
            )
        }
    }

    /** Accepted batches cannot expose a previous review when their progress screen is popped. */
    @Test
    fun submittedProgressNeverReturnsToAnEditableReview() {
        for (screen in listOf(BulkScreen.Progress, BulkScreen.RetryReview)) {
            val stack =
                dashboardBackStack(NavigationState(tab = 2, bulk = BulkFlowState(screen = screen)))
            assertEquals(
                listOf(AppDestination.Home, AppDestination.Activity, AppDestination.BulkProgress),
                stack.take(3),
            )
            assertFalse(AppDestination.BulkReview in stack)
            assertFalse(AppDestination.BulkRecipients in stack)
            if (screen == BulkScreen.RetryReview)
                assertEquals(AppDestination.BulkRetryReview, stack.last())
        }
    }

    /** Route identities are static and do not change when a payment draft or sheet changes. */
    @Test
    fun sheetsAndSensitiveInputsAreNotNavigationArguments() {
        val navigation = NavigationState(tab = 1)
        assertEquals(
            dashboardBackStack(navigation),
            dashboardBackStack(navigation.copy(sheet = Sheet.Sms("request-fixture"))),
        )
        val signup = AuthUiState(screen = AuthScreen.SignIn)
        assertEquals(
            accountBackStack(signup),
            accountBackStack(
                signup.copy(
                    contact = "fixture@example.test",
                    password = "private",
                    code = "000123",
                ),
            ),
        )
        val all = AppDestination.accountRoutes + AppDestination.dashboardRoutes
        assertEquals(AppDestination.entries.toSet(), all.toSet())
        assertEquals(all.size, all.map { it.route }.distinct().size)
    }
}
