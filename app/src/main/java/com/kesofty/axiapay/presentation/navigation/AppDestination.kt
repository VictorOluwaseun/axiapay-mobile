package com.kesofty.axiapay.presentation.navigation

import com.kesofty.axiapay.presentation.BulkScreen
import com.kesofty.axiapay.presentation.NavigationState
import com.kesofty.axiapay.presentation.auth.AuthScreen
import com.kesofty.axiapay.presentation.auth.AuthUiState

/** Static routes carry no credentials, payment input, or duplicate copies of workflow state. */
enum class AppDestination(val route: String) {
    Welcome("account/welcome"),
    SignIn("account/sign-in"),
    CreateAccount("account/create"),
    Verification("account/verification"),
    AccountOverview("account/overview"),
    RestoringSession("account/restoring"),
    SessionRecovery("account/recovery"),
    Dashboard("dashboard"),
    Home("home"),
    Payments("payments"),
    Activity("activity"),
    Plans("plans"),
    BulkServices("bulk/services"),
    BulkRecipients("bulk/recipients"),
    BulkImport("bulk/import"),
    BulkReview("bulk/review"),
    BulkProgress("bulk/progress"),
    BulkRetryReview("bulk/retry-review");

    companion object {
        /** Account routes share the Activity-owned authentication and payment coordinators. */
        val accountRoutes = entries.takeWhile { it != Home }

        /** Dashboard destinations retain the same draft and execution owner across tab changes. */
        val dashboardRoutes = entries.dropWhile { it != Home }
    }
}

/**
 * Project account workflow state into its permitted history. A restored controller cannot reopen
 * abandoned proof or bypass session recovery; the workflow remains the sole state owner.
 */
fun accountBackStack(state: AuthUiState): List<AppDestination> =
    when (state.screen) {
        AuthScreen.Welcome -> listOf(AppDestination.Welcome)
        AuthScreen.SignIn -> listOf(AppDestination.Welcome, AppDestination.SignIn)
        AuthScreen.CreateAccount -> listOf(AppDestination.Welcome, AppDestination.CreateAccount)
        AuthScreen.Verification ->
            listOf(
                AppDestination.Welcome,
                if (state.isCreatingAccount) AppDestination.CreateAccount
                else AppDestination.SignIn,
                AppDestination.Verification,
            )
        AuthScreen.Complete ->
            listOf(
                if (!state.isDemo && state.account != null) AppDestination.Dashboard
                else AppDestination.Welcome,
                AppDestination.AccountOverview,
            )
        AuthScreen.Restoring -> listOf(AppDestination.Welcome, AppDestination.RestoringSession)
        AuthScreen.SessionRecovery -> listOf(AppDestination.Welcome, AppDestination.SessionRecovery)
        AuthScreen.Dashboard -> listOf(AppDestination.Dashboard)
    }

/**
 * Preserve the existing Back contract: tabs return Home, editable bulk screens return through
 * service selection, and submitted progress never returns to a spendable review.
 */
fun dashboardBackStack(state: NavigationState): List<AppDestination> {
    val tab =
        when (state.tab) {
            0 -> AppDestination.Home
            1 -> AppDestination.Payments
            2 -> AppDestination.Activity
            else -> AppDestination.Plans
        }
    val base = if (tab == AppDestination.Home) listOf(tab) else listOf(AppDestination.Home, tab)
    val services = listOf(AppDestination.BulkServices)
    val recipients = services + AppDestination.BulkRecipients
    return base +
        when (state.bulk.screen) {
            null -> emptyList()
            BulkScreen.Services -> services
            BulkScreen.Recipients -> recipients
            BulkScreen.Import -> recipients + AppDestination.BulkImport
            BulkScreen.Review -> recipients + AppDestination.BulkReview
            BulkScreen.Progress -> listOf(AppDestination.BulkProgress)
            BulkScreen.RetryReview ->
                listOf(AppDestination.BulkProgress, AppDestination.BulkRetryReview)
        }
}
