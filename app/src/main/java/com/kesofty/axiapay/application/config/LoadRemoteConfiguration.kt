package com.kesofty.axiapay.application.config

import com.kesofty.axiapay.domain.config.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/**
 * Restore saved display policy before online revalidation; no authentication operation is replayed.
 */
class LoadRemoteConfiguration(private val repository: RemoteConfigRepository) {
    /** Each collection belongs to its caller and retains cached policy if refresh fails. */
    operator fun invoke(): Flow<RemoteConfigurationUpdate> =
        flow<RemoteConfigurationUpdate> {
                repository.readCached()?.let { emit(RemoteConfigurationUpdate.Cached(it)) }
                emit(RemoteConfigurationUpdate.Refreshed(repository.refresh()))
            }
            .catch { failure ->
                if (failure is CancellationException) {
                    throw failure
                }
                emit(
                    RemoteConfigurationUpdate.Failed(
                        (failure as? RemoteConfigFailure)?.issue
                            ?: RemoteConfigIssue.InvalidResponse,
                    ),
                )
            }
}

/** Ordered configuration progress; failure never labels a saved snapshot as newly validated. */
sealed interface RemoteConfigurationUpdate {
    /** Previously committed policy is usable for display while revalidation runs. */
    data class Cached(val configuration: RemoteConfiguration) : RemoteConfigurationUpdate

    /** Online 200/304 validation completed; replacement publication follows atomic persistence. */
    data class Refreshed(val configuration: RemoteConfiguration) : RemoteConfigurationUpdate

    /** Revalidation failed; callers retain their last available policy or first-launch defaults. */
    data class Failed(val issue: RemoteConfigIssue) : RemoteConfigurationUpdate
}
