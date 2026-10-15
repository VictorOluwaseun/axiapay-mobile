package com.kesofty.axiapay.data

import android.util.AtomicFile
import com.kesofty.axiapay.domain.TextCheckpoint
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** App-private no-backup storage; the owner serializes access and only a small ID enters Bundle. */
class AtomicTextCheckpoint(
    private val file: File,
    private val worker: CoroutineDispatcher,
    private val required: Boolean = false,
) : TextCheckpoint {
    /**
     * AtomicFile restores its backup after interrupted writes; missing required state is an error.
     */
    override suspend fun read(): String? =
        withContext(worker) {
            val atomic = AtomicFile(file)
            if (!file.exists() && !File(file.path + ".bak").exists() && !required)
                return@withContext null
            atomic.openRead().bufferedReader().use { it.readText() }
        }

    /** Finishes one complete replacement or restores the previous snapshot, including on error. */
    override suspend fun write(value: String) =
        withContext(worker) {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(value.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (failure: Throwable) {
                atomic.failWrite(stream)
                throw failure
            }
        }
}
