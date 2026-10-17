package com.kesofty.axiapay.data.purchases

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Observable durability operations for the private recovery journal. Keeping this storage-only
 * boundary explicit permits sync/rename fault injection without changing workflow contracts.
 */
internal interface PurchaseJournalCommitOperations {
    /** Persist staged bytes; failure must propagate before the committed file is replaced. */
    fun syncFile(stream: FileOutputStream)

    /**
     * Atomically replace one file within the same directory, or throw without an unsafe fallback.
     */
    fun replace(source: File, destination: File)

    /** Persist the renamed directory entry; failure leaves the commit uncertain and blocks POST. */
    fun syncDirectory(directory: File)
}

/** Android/POSIX commit operations propagate errors instead of logging a successful return. */
internal object AndroidPurchaseJournalCommitOperations : PurchaseJournalCommitOperations {
    /** FileDescriptor.sync throws on fsync failure; closing the stream remains the caller's job. */
    override fun syncFile(stream: FileOutputStream) {
        stream.fd.sync()
    }

    /** App-private same-directory atomic replacement requires API 26, the existing minimum SDK. */
    override fun replace(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /** Sync the existing no-backup directory before reporting the renamed journal durable. */
    override fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}
