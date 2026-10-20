package com.kesofty.axiapay

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import com.kesofty.axiapay.presentation.AxiaViewModel

/**
 * Await the first Compose surface and the preview's restored checkpoints before editing forms or
 * preferences. Hilt construction now happens in the Compose root, after Activity.onCreate.
 */
internal fun AndroidComposeTestRule<*, MainActivity>.awaitDemoWelcome() {
    waitUntil(10_000) {
        onAllNodesWithText("Explore demo")
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()
    }
    val owner = runOnIdle { ViewModelProvider(activity)[AxiaViewModel::class.java] }
    waitUntil(10_000) { !owner.uiState.value.isRestoring }
}
