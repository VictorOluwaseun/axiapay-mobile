package com.kesofty.axiapay.data

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.kesofty.axiapay.domain.InternetConnectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * Uses the application service; no Activity, polling, probes, SMS permissions or payment actions.
 */
class AndroidInternetConnectionRepository(private val manager: ConnectivityManager) :
    InternetConnectionRepository {
    override fun currentIsOnline(): Boolean {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override val isOnline: Flow<Boolean> = callbackFlow {
        val tracker = DefaultNetworkTracker()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    tracker.available(network.networkHandle)
                    // Availability alone is not internet access. API 26+ next supplies
                    // capabilities.
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    tracker
                        .capabilities(
                            network.networkHandle,
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                            capabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                            ),
                        )
                        ?.let { trySend(it) }
                }

                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    tracker.blocked(network.networkHandle, blocked)?.let { trySend(it) }
                }

                override fun onLost(network: Network) {
                    tracker.lost(network.networkHandle)?.let { trySend(it) }
                }
            }
        // Main-thread registration, initial snapshot and callbacks are serialized. Register first
        // so a change during startup cannot be missed. Never query synchronous state in a callback.
        manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        try {
            trySend(currentIsOnline())
            awaitClose {}
        } finally {
            manager.unregisterNetworkCallback(callback)
        }
    }
        .buffer(Channel.CONFLATED)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Main.immediate)
}
