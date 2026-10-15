package com.kesofty.axiapay.di

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.kesofty.axiapay.data.AtomicTextCheckpoint
import com.kesofty.axiapay.domain.TextCheckpoint
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import java.io.File
import java.util.UUID
import javax.inject.Qualifier
import kotlinx.coroutines.Dispatchers

/** Marks the payment session checkpoint separately from presentation drafts. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SessionCheckpoint

/** Marks the navigation checkpoint; it never stores accepted payment execution state. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class NavigationCheckpoint

/** Supplies private session paths; Bundle retains UUIDs only, never thousands of recipients. */
@Module
@InstallIn(ViewModelComponent::class)
internal object CheckpointModule {
    /**
     * Retains the same scoped wallet checkpoint through recreation without sharing other owners.
     */
    @Provides
    @ViewModelScoped
    @SessionCheckpoint
    fun session(@ApplicationContext context: Context, handle: SavedStateHandle): TextCheckpoint =
        checkpoint(context, handle, "session")

    /** Separates draft persistence from atomic wallet/reservation records. */
    @Provides
    @ViewModelScoped
    @NavigationCheckpoint
    fun navigation(@ApplicationContext context: Context, handle: SavedStateHandle): TextCheckpoint =
        checkpoint(context, handle, "navigation")

    /**
     * Allocates an owner key once; existing IDs must recover their file rather than reset silently.
     */
    private fun checkpoint(
        context: Context,
        handle: SavedStateHandle,
        kind: String,
    ): TextCheckpoint {
        val key = "checkpoint.$kind.v1"
        val existing = handle.get<String>(key)
        val identity = existing ?: UUID.randomUUID().toString().also { handle[key] = it }
        require(
            runCatching { UUID.fromString(identity).toString() == identity }.getOrDefault(false),
        )
        return AtomicTextCheckpoint(
            File(context.noBackupFilesDir, "demo-checkpoints/$identity.$kind.json"),
            Dispatchers.IO,
            required = handle.get<Boolean>("$key.ready") == true,
        )
    }
}
