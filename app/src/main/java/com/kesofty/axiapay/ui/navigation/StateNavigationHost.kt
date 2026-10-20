package com.kesofty.axiapay.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kesofty.axiapay.presentation.navigation.AppDestination

/**
 * Render a workflow's navigation projection with Navigation Compose. Saved controller history is
 * reconciled after restoration, never copied back into business state or used to replay an intent.
 * Back enters the workflow first so cancellation, draft retention, and result ownership still run.
 */
@Composable
internal fun StateNavigationHost(
    destinations: List<AppDestination>,
    backStack: List<AppDestination>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    controller: NavHostController = rememberNavController(),
    content: @Composable (AppDestination) -> Unit,
) {
    val entry by controller.currentBackStackEntryAsState()
    val current = rememberUpdatedState(backStack.last())
    val render = rememberUpdatedState(content)
    NavHost(
        navController = controller,
        startDestination = destinations.first().route,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        destinations.forEach { destination ->
            composable(destination.route) {
                // Outgoing or OS-restored entries must not render a different workflow visit.
                if (destination == current.value) {
                    Box(Modifier.fillMaxSize().testTag("navigation-${destination.route}")) {
                        render.value(destination)
                    }
                }
            }
        }
    }
    LaunchedEffect(backStack, entry) { controller.reconcile(backStack) }
    BackHandler(enabled = backStack.size > 1, onBack = onBack)
}

/**
 * Retain existing ancestors and their saveable UI state. Unrelated histories are removed before a
 * new root is installed, so Back cannot expose an earlier sign-in or submitted payment review.
 */
private fun NavHostController.reconcile(backStack: List<AppDestination>) {
    val target = backStack.last().route
    if (currentDestination?.route == target) return
    if (popBackStack(target, inclusive = false)) return
    val ancestor = backStack.indexOfLast { containsRoute(it.route) }
    if (ancestor >= 0) {
        popBackStack(backStack[ancestor].route, inclusive = false)
    }
    backStack.drop(ancestor + 1).forEachIndexed { index, destination ->
        navigate(destination.route) {
            launchSingleTop = true
            if (ancestor < 0 && index == 0) {
                popUpTo(graph.id) { inclusive = false }
            }
        }
    }
}

/** Navigation's lookup reports an absent restored ancestor with IllegalArgumentException. */
private fun NavHostController.containsRoute(route: String): Boolean =
    try {
        getBackStackEntry(route)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
