package com.kesofty.axiapay

import com.kesofty.axiapay.application.config.RemoteConfigurationUpdate
import com.kesofty.axiapay.domain.config.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Exercise local-first ordering, online failure and collector-owned cancellation independently of
 * HTTP.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadRemoteConfigurationTest {
    private val saved =
        RemoteConfiguration(
            "v1",
            AuthenticationMethods(false, false),
            AuthenticationMethods(false, true),
        )

    /** A suspended network check cannot delay publishing a complete saved policy. */
    @Test
    fun savedPolicyIsEmittedBeforeTheNetworkCompletes() = runTest {
        val network = CompletableDeferred<Unit>()
        val fresh = saved.copy(version = "v2", login = AuthenticationMethods(true, false))
        val updates = mutableListOf<RemoteConfigurationUpdate>()
        val load =
            configurationLoader(cached = { saved }) {
                network.await()
                fresh
            }
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) { load().toList(updates) }
        assertEquals(listOf(RemoteConfigurationUpdate.Cached(saved)), updates)
        assertTrue(collection.isActive)
        network.complete(Unit)
        collection.join()
        assertEquals(
            listOf(
                RemoteConfigurationUpdate.Cached(saved),
                RemoteConfigurationUpdate.Refreshed(fresh),
            ),
            updates,
        )
    }

    /** Missing local settings do not become a fabricated remote version or skip online refresh. */
    @Test
    fun aCacheMissEmitsOnlyTheValidatedOnlinePolicy() = runTest {
        val updates = configurationLoader { saved }().toList()
        assertEquals(listOf(RemoteConfigurationUpdate.Refreshed(saved)), updates)
    }

    /**
     * An outage follows the cached result with an explicit failure instead of replacing its
     * switches.
     */
    @Test
    fun failedRefreshRetainsTheSavedResultAndReportsItsIssue() = runTest {
        val updates =
            configurationLoader(cached = { saved }) {
                    throw RemoteConfigFailure(RemoteConfigIssue.Network)
                }()
                .toList()
        assertEquals(
            listOf(
                RemoteConfigurationUpdate.Cached(saved),
                RemoteConfigurationUpdate.Failed(RemoteConfigIssue.Network),
            ),
            updates,
        )
    }

    /**
     * Cancelling the owner never becomes an availability error or publishes a late remote value.
     */
    @Test
    fun cancellationAfterCachePublicationDoesNotEmitFailure() = runTest {
        val updates = mutableListOf<RemoteConfigurationUpdate>()
        val load = configurationLoader(cached = { saved }) { awaitCancellation() }
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) { load().toList(updates) }
        collection.cancelAndJoin()
        assertEquals(listOf(RemoteConfigurationUpdate.Cached(saved)), updates)
    }

    /** Cancelling disk restoration cannot start an orphan refresh after the caller has gone. */
    @Test
    fun cancellationDuringTheLocalReadDoesNotStartNetworkWork() = runTest {
        var refreshes = 0
        val updates = mutableListOf<RemoteConfigurationUpdate>()
        val load =
            configurationLoader(cached = { awaitCancellation() }) {
                refreshes++
                saved
            }
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) { load().toList(updates) }
        collection.cancelAndJoin()
        assertEquals(0, refreshes)
        assertTrue(updates.isEmpty())
    }
}
