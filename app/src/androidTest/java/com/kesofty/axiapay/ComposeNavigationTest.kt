package com.kesofty.axiapay

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.auth.AuthScreen
import com.kesofty.axiapay.presentation.auth.AuthUiState
import com.kesofty.axiapay.presentation.navigation.*
import com.kesofty.axiapay.ui.navigation.StateNavigationHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Navigation Compose histories and saved-instance-state recovery without customer storage. */
class ComposeNavigationTest {
    @get:Rule val compose = createComposeRule()

    /** Each tab replaces the previous tab, and system Back enters the reducer exactly once. */
    @Test
    fun tabHistoryReturnsHomeWithoutReplayingEarlierTabs() {
        var navigation by mutableStateOf(NavigationState())
        var backCount = 0
        lateinit var controller: NavHostController
        compose.setContent {
            controller = rememberNavController()
            StateNavigationHost(
                AppDestination.dashboardRoutes,
                dashboardBackStack(navigation),
                onBack = {
                    backCount++
                    navigation = navigation.copy(tab = 0)
                },
                controller = controller,
            ) {
                Text(it.route)
            }
        }
        for ((tab, destination) in
            listOf(
                1 to AppDestination.Payments,
                2 to AppDestination.Activity,
                3 to AppDestination.Plans,
            )) {
            compose.runOnIdle { navigation = navigation.copy(tab = tab) }
            compose.onNodeWithTag("navigation-${destination.route}").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(
                    AppDestination.Home.route,
                    controller.previousBackStackEntry?.destination?.route,
                )
            }
        }
        Espresso.pressBack()
        compose.onNodeWithTag("navigation-home").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, backCount)
            assertEquals(0, navigation.tab)
            assertNull(controller.previousBackStackEntry)
        }
    }

    /**
     * Fresh auth state overrides an OS-restored form; private input never enters route arguments.
     */
    @Test
    fun coldRestorationCannotReopenAbandonedProof() {
        val restoration = StateRestorationTester(compose)
        lateinit var change: (AuthUiState) -> Unit
        lateinit var controller: NavHostController
        restoration.setContent {
            var state by remember { mutableStateOf(AuthUiState()) }
            change = { state = it }
            controller = rememberNavController()
            StateNavigationHost(
                AppDestination.accountRoutes,
                accountBackStack(state),
                onBack = { state = AuthUiState() },
                controller = controller,
            ) {
                Text(it.route)
            }
        }
        compose.runOnIdle {
            change(
                AuthUiState(
                    screen = AuthScreen.Verification,
                    contact = "fixture@example.test",
                    code = "000123",
                ),
            )
        }
        compose.onNodeWithTag("navigation-account/verification").assertIsDisplayed()
        compose.runOnIdle {
            val arguments = requireNotNull(controller.currentBackStackEntry?.arguments)
            // String navigation adds this framework Intent even for an argument-free route.
            assertEquals(setOf(NavController.KEY_DEEP_LINK_INTENT), arguments.keySet())
            @Suppress("DEPRECATION")
            val route = arguments.getParcelable<Intent>(NavController.KEY_DEEP_LINK_INTENT)?.data
            assertEquals("/account/verification", route?.path)
            assertNull(route?.query)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("navigation-account/welcome").assertIsDisplayed()
        compose.runOnIdle { assertNull(controller.previousBackStackEntry) }
    }

    /** Restoring accepted progress retains its parent tab and emits no Back or payment command. */
    @Test
    fun acceptedProgressRestoresWithoutReturningToReviewOrEmittingActions() {
        val restoration = StateRestorationTester(compose)
        val state = NavigationState(tab = 2, bulk = BulkFlowState(screen = BulkScreen.Progress))
        var actions = 0
        lateinit var controller: NavHostController
        restoration.setContent {
            controller = rememberNavController()
            StateNavigationHost(
                AppDestination.dashboardRoutes,
                dashboardBackStack(state),
                onBack = { actions++ },
                controller = controller,
            ) {
                Text(it.route)
            }
        }
        compose.onNodeWithTag("navigation-bulk/progress").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("navigation-bulk/progress").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, actions)
            assertEquals(
                AppDestination.Activity.route,
                controller.previousBackStackEntry?.destination?.route,
            )
        }
    }

    /** Controller-only Back cannot independently reopen a workflow route the reducer still owns. */
    @Test
    fun staleControllerHistoryIsReconciledWithoutDispatchingAWorkflowAction() {
        var actions = 0
        lateinit var controller: NavHostController
        compose.setContent {
            controller = rememberNavController()
            StateNavigationHost(
                AppDestination.dashboardRoutes,
                dashboardBackStack(NavigationState(tab = 3)),
                onBack = { actions++ },
                controller = controller,
            ) {
                Text(it.route)
            }
        }
        compose.onNodeWithTag("navigation-plans").assertIsDisplayed()
        compose.runOnIdle { controller.popBackStack() }
        compose.onNodeWithTag("navigation-plans").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, actions) }
    }
}
