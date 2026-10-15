package com.kesofty.axiapay.data.imports

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.kesofty.axiapay.domain.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads only a user-granted SAF URI; owns no cache, persistence, upload or wallet transaction. */
class AndroidRecipientImportRepository(
    private val resolver: ContentResolver,
    private val dispatcher: CoroutineDispatcher,
    private val parser: RecipientDocumentParser,
) : RecipientImportRepository {
    /**
     * Bounds actual bytes even when the provider omits or misreports the size; closes all handles.
     */
    override suspend fun read(reference: String): ImportDocumentResult =
        withContext(dispatcher) {
            try {
                val uri = Uri.parse(reference)
                if (uri.scheme != "content")
                    return@withContext ImportDocumentResult.Failure(ImportFileIssue.Unreadable)
                val name = fileName(uri)
                val bytes =
                    resolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > RecipientDocumentParser.MAX_FILE_BYTES)
                                throw ImportReadException(ImportFileIssue.TooLarge)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: return@withContext ImportDocumentResult.Failure(ImportFileIssue.Unreadable)
                ensureActive()
                val document = parser.parse(name, bytes)
                ensureActive()
                ImportDocumentResult.Success(document)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: org.apache.poi.EncryptedDocumentException) {
                ImportDocumentResult.Failure(ImportFileIssue.Encrypted, failure)
            } catch (failure: ImportReadException) {
                ImportDocumentResult.Failure(failure.issue, failure)
            } catch (failure: SecurityException) {
                ImportDocumentResult.Failure(ImportFileIssue.Unreadable, failure)
            } catch (failure: java.io.IOException) {
                ImportDocumentResult.Failure(ImportFileIssue.Unreadable, failure)
            } catch (failure: Exception) {
                ImportDocumentResult.Failure(ImportFileIssue.InvalidFile, failure)
            }
        }

    /** Reads display metadata only; document names are never used as filesystem paths. */
    private fun fileName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor
            ->
            if (!cursor.moveToFirst()) throw ImportReadException(ImportFileIssue.Unreadable)
            cursor.getString(0).take(160)
        } ?: throw ImportReadException(ImportFileIssue.Unreadable)
}
