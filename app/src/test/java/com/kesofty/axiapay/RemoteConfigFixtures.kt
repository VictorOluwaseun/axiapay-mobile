package com.kesofty.axiapay

import com.kesofty.axiapay.application.config.LoadRemoteConfiguration
import com.kesofty.axiapay.domain.config.*

/** Explicit test policy for auth/wallet tests that exercise other workflows without network IO. */
internal fun enabledRemoteConfiguration() = configurationLoader {
    RemoteConfiguration(
        "fixture",
        AuthenticationMethods(true, true),
        AuthenticationMethods(true, true),
    )
}

/**
 * Keep saved policy and online results independently controllable in application/presentation
 * tests.
 */
internal fun configurationLoader(
    cached: suspend () -> RemoteConfiguration? = { null },
    refreshConfiguration: suspend () -> RemoteConfiguration,
) =
    LoadRemoteConfiguration(
        object : RemoteConfigRepository {
            /** Simulate only the local storage read. */
            override suspend fun readCached() = cached()

            /** Simulate only online revalidation, including suspension or failure. */
            override suspend fun refresh() = refreshConfiguration()
        },
    )
