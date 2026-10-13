package com.kesofty.axiapay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * Radio changes are only allowed on an emulator, and original settings are restored after tests.
 */
internal class DeviceInternet {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var originalWifi: Boolean? = null
    private var originalData: Boolean? = null

    fun capture() {
        assumeTrue(
            "Network-switch tests require an emulator",
            Build.FINGERPRINT.startsWith("generic") ||
                Build.MODEL.contains("sdk") ||
                Build.MODEL.contains("Emulator"),
        )
        originalWifi = Settings.Global.getInt(context.contentResolver, "wifi_on", 0) != 0
        originalData = Settings.Global.getInt(context.contentResolver, "mobile_data", 0) != 0
    }

    fun setOnline(online: Boolean) {
        setRadio("wifi", online)
        setRadio("data", online)
    }

    fun isOnline(): Boolean {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun restore() {
        originalWifi?.let { setRadio("wifi", it) }
        originalData?.let { setRadio("data", it) }
    }

    private fun setRadio(radio: String, enabled: Boolean) {
        val action = if (enabled) "enable" else "disable"
        ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("svc $radio $action"),
            )
            .use { it.readBytes() }
    }
}
