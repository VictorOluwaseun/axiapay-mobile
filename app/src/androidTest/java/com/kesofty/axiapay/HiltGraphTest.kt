package com.kesofty.axiapay

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import com.kesofty.axiapay.domain.ConnectionMode
import com.kesofty.axiapay.domain.purchases.PurchaseService
import com.kesofty.axiapay.presentation.*
import com.kesofty.axiapay.presentation.auth.AuthIntent
import com.kesofty.axiapay.presentation.auth.AuthViewModel
import com.kesofty.axiapay.presentation.purchases.PurchaseIntent
import com.kesofty.axiapay.presentation.purchases.PurchaseScreen
import com.kesofty.axiapay.presentation.purchases.PurchaseViewModel
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test

/** Exercises the installed flavor's real Hilt graph without replacing its bindings in the test. */
class HiltGraphTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Production flavor composition enables live wallet purchases only outside the demo flavor. */
    @Test
    fun telecomCapabilityIsSelectedAtTheFlavorCompositionBoundary() {
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[PurchaseViewModel::class.java]
            assertEquals(BuildConfig.FLAVOR != "demo", vm.uiState.value.enabled)
            vm.accept(PurchaseIntent.Bind(null, "isolated-test-visit", PurchaseService.Data))
            assertEquals(
                if (BuildConfig.FLAVOR == "demo") PurchaseScreen.Hidden else PurchaseScreen.SignIn,
                vm.uiState.value.screen,
            )
        }
    }

    /**
     * Signed-out non-demo entry must render the live sign-in boundary instead of a demo purchase.
     */
    @Test
    fun realPurchaseEntriesCannotUseTheDemoLedgerWithoutSignIn() {
        Assume.assumeTrue(BuildConfig.FLAVOR != "demo")
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
        for (service in listOf("Airtime", "Data", "Electricity")) {
            compose
                .onNode(hasTextExactly(service) and hasClickAction())
                .performScrollTo()
                .performClick()
            compose.onNodeWithTag("telecom-sign-in").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Confirm demo payment").assertDoesNotExist()
            Espresso.pressBack()
        }
    }

    /**
     * Queries and commands share one repository per ViewModel. Independent owner keys must not
     * share wallets, and recreation/auth navigation must retain the original funded session.
     */
    @Test
    fun sessionBindingsShareWithinOwnersAndRemainIsolatedAcrossOwners() {
        lateinit var primary: AxiaViewModel
        lateinit var independent: AxiaViewModel
        lateinit var auth: AuthViewModel
        compose.runOnIdle {
            val provider = ViewModelProvider(compose.activity)
            primary = provider[AxiaViewModel::class.java]
            independent = provider["independent-payment-preview", AxiaViewModel::class.java]
            auth = provider[AuthViewModel::class.java]
        }
        compose.waitUntil(10_000) {
            !primary.uiState.value.isRestoring && !independent.uiState.value.isRestoring
        }
        compose.runOnIdle {
            primary.accept(AxiaIntent.SetConnectionMode(ConnectionMode.ForceOnline))
            primary.accept(AxiaIntent.OpenService("Add money"))
            primary.accept(AxiaIntent.EditPayment(PaymentIntent.Amount("5000")))
            primary.accept(AxiaIntent.EditPayment(PaymentIntent.Review))
            primary.accept(AxiaIntent.ConfirmPayment)
            auth.accept(AuthIntent.ExploreDemo)
        }
        compose.waitUntil(10_000) { primary.uiState.value.navigation.sheet is Sheet.Receipt }
        compose.runOnIdle {
            assertEquals(13_345_000, primary.uiState.value.session.balance)
            assertEquals(12_845_000, independent.uiState.value.session.balance)
            assertTrue(primary.uiState.value.navigation.sheet is Sheet.Receipt)
            assertNull(independent.uiState.value.navigation.sheet)
            auth.accept(AuthIntent.OpenAccount)
            auth.accept(AuthIntent.ExploreDemo)
        }
        compose.onNodeWithText("All taken care of.").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            val provider = ViewModelProvider(compose.activity)
            assertSame(primary, provider[AxiaViewModel::class.java])
            assertSame(
                independent,
                provider["independent-payment-preview", AxiaViewModel::class.java],
            )
            assertSame(auth, provider[AuthViewModel::class.java])
            assertEquals(13_345_000, primary.uiState.value.session.balance)
            assertEquals(12_845_000, independent.uiState.value.session.balance)
        }
        compose.onNodeWithText("All taken care of.").assertIsDisplayed()
    }
}
