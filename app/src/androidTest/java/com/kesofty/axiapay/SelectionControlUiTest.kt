package com.kesofty.axiapay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod
import com.kesofty.axiapay.domain.ElectricityMeterType
import com.kesofty.axiapay.ui.auth.AuthContactSelector
import com.kesofty.axiapay.ui.auth.AuthMethodSelector
import com.kesofty.axiapay.ui.components.ElectricityMeterTypeChoices
import com.kesofty.axiapay.ui.components.SelectionChip
import com.kesofty.axiapay.ui.theme.AxiapayTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Guards the reported label-jump bug and the shared control's accessible disabled contract. */
class SelectionControlUiTest {
    @get:Rule val compose = createComposeRule()

    /** Meter and auth labels keep their exact bounds in both palettes, scales and directions. */
    @Test
    fun selectionNeverMovesOrResizesLabels() {
        val offline = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val meter = mutableStateOf(ElectricityMeterType.Prepaid)
        val contact = mutableStateOf(AuthContactType.Phone)
        val method = mutableStateOf(AuthMethod.OneTimeCode)
        compose.setContent {
            AxiapayTheme(offline = offline.value) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale.value),
                    LocalLayoutDirection provides direction.value,
                ) {
                    Column(
                        Modifier.width(320.dp).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ElectricityMeterTypeChoices(meter.value, { meter.value = it })
                        AuthContactSelector(contact.value, true) { contact.value = it }
                        AuthMethodSelector(method.value, true) { method.value = it }
                    }
                }
            }
        }
        for (palette in listOf(false, true)) {
            for (scale in listOf(1f, 1.3f)) {
                for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                    compose.runOnIdle {
                        offline.value = palette
                        fontScale.value = scale
                        direction.value = layout
                        meter.value = ElectricityMeterType.Prepaid
                        contact.value = AuthContactType.Phone
                        method.value = AuthMethod.OneTimeCode
                    }
                    val before = labelBounds()
                    compose.onNodeWithTag("meter-type-Postpaid").performClick().assertIsSelected()
                    compose.onNodeWithTag("auth-contact-email").performClick().assertIsSelected()
                    compose.onNodeWithTag("auth-method-password").performClick().assertIsSelected()
                    assertEquals(
                        "Selecting at offline=$palette scale=$scale layout=$layout",
                        before,
                        labelBounds(),
                    )
                    compose.onNodeWithTag("meter-type-Prepaid").performClick().assertIsSelected()
                    compose.onNodeWithTag("auth-contact-phone").performClick().assertIsSelected()
                    compose.onNodeWithTag("auth-method-code").performClick().assertIsSelected()
                    assertEquals(
                        "Deselecting at offline=$palette scale=$scale layout=$layout",
                        before,
                        labelBounds(),
                    )
                }
            }
        }
    }

    /** A selected disabled card remains a single radio target and cannot invoke its callback. */
    @Test
    fun disabledChoiceKeepsRadioSemanticsAndMinimumTarget() {
        var selections = 0
        compose.setContent {
            AxiapayTheme {
                Row(Modifier.selectableGroup()) {
                    SelectionChip(
                        "Prepaid",
                        true,
                        { selections++ },
                        Modifier.testTag("disabled-choice"),
                        enabled = false,
                    )
                }
            }
        }
        compose
            .onNodeWithTag("disabled-choice")
            .assertIsSelected()
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, selections) }
    }

    /** Reads actual text nodes, so a stationary outer card cannot hide inline label movement. */
    private fun labelBounds(): Map<String, Rect> =
        listOf("Prepaid", "Postpaid", "Phone", "Email", "One-time code", "Password").associateWith {
            compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
}
