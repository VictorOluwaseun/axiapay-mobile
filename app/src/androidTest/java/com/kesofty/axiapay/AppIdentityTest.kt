package com.kesofty.axiapay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks each installed variant's application ID and launcher label, independently of namespace.
 */
@RunWith(AndroidJUnit4::class)
class AppIdentityTest {
    /** Flavors must target distinct sandboxes while keeping the existing Kotlin namespace. */
    @Test
    fun installedApplicationMatchesSelectedEnvironment() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val expected =
            when (BuildConfig.FLAVOR) {
                "dev" -> "com.kesofty.axiapay.dev" to "AxiaPay Dev"
                "demo" -> "com.kesofty.axiapay.demo" to "AxiaPay Demo"
                "staging" -> "com.kesofty.axiapay.staging" to "AxiaPay Staging"
                "prod" -> "com.kesofty.axiapay" to "AxiaPay"
                else -> error("Unexpected environment: ${BuildConfig.FLAVOR}")
            }
        assertEquals(expected.first, appContext.packageName)
        assertEquals(expected.first, BuildConfig.APPLICATION_ID)
        assertEquals(
            expected.second,
            appContext.applicationInfo.loadLabel(appContext.packageManager).toString(),
        )
    }
}
