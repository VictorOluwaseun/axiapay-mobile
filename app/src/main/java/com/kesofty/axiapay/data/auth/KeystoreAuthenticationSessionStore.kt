package com.kesofty.axiapay.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.kesofty.axiapay.domain.auth.*
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Atomically stores one AES-GCM encrypted bearer record in Android's no-backup directory using a
 * non-exportable Android Keystore key. All crypto/IO is main-safe and serialized. Passwords,
 * challenges and codes are never stored. Corruption/key loss removes local access and requires a
 * new sign-in; publication follows successful atomic persistence, with no plaintext fallback.
 */
class KeystoreAuthenticationSessionStore(context: Context, private val io: CoroutineDispatcher) :
    AuthenticationSessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "authentication/session.v1"))
    private val keyAlias = context.packageName + ".authentication.v1"
    private val associatedData = keyAlias.toByteArray(Charsets.UTF_8)
    private val lock = Mutex()

    /** Authenticates bounded ciphertext; corruption or key loss requires a fresh sign-in. */
    override suspend fun read(): StoredAuthenticationSession? =
        withContext(io) {
            lock.withLock { readRecord() }
        }

    /** Commits an encrypted token pair before application access is published. */
    override suspend fun write(session: StoredAuthenticationSession) =
        withContext(io) {
            lock.withLock { writeRecord(session) }
        }

    /** Serialize compare-and-replace with login and logout using the same Keystore storage lock. */
    override suspend fun replace(
        expected: StoredAuthenticationSession,
        replacement: StoredAuthenticationSession,
    ): Boolean =
        withContext(io) {
            lock.withLock {
                if (readRecord() != expected) return@withLock false
                writeRecord(replacement)
                true
            }
        }

    /** Persist the whole pair in one authenticated atomic file; callers own the storage mutex. */
    private suspend fun writeRecord(session: StoredAuthenticationSession) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(associatedData)
            val payload =
                JSONObject()
                    .put("accessToken", session.accessToken)
                    .put("expiresAt", session.expiresAt.toString())
                    .put("apiIdentity", session.apiIdentity)
                    .put("accountId", session.accountId)
                    .put("refreshToken", session.refreshCredentials?.token)
                    .put("refreshExpiresAt", session.refreshCredentials?.expiresAt?.toString())
                    .put("sessionId", session.refreshCredentials?.sessionId)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            val encrypted = cipher.doFinal(payload)
            require(cipher.iv.size == 12)
            val bytes = byteArrayOf(1, 12) + cipher.iv + encrypted
            currentCoroutineContext().ensureActive()
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                currentCoroutineContext().ensureActive()
                file.finishWrite(stream)
                check(readRecord() == session)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            } finally {
                payload.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw AuthenticationFailure(AccountAccessIssue.SecureStorage)
        }
    }

    /** Atomically removes only the read session, preserving credentials from a newer login. */
    override suspend fun clear(expected: StoredAuthenticationSession) =
        withContext(io) {
            lock.withLock {
                if (readRecord() != expected) {
                    return@withLock
                }
                deleteRecord()
            }
        }

    /**
     * Reads under the caller's IO dispatcher and mutex; transient Keystore/IO failure stays
     * retryable.
     */
    private fun readRecord(): StoredAuthenticationSession? {
        try {
            val bytes =
                file.openRead().use { stream ->
                    val buffer = ByteArray(65537)
                    var size = 0
                    while (size < buffer.size) {
                        val count = stream.read(buffer, size, buffer.size - size)
                        if (count < 0) {
                            break
                        }
                        size += count
                    }
                    require(size in 30..65536)
                    buffer.copyOf(size)
                }
            require(bytes[0].toInt() == 1 && bytes[1].toInt() == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(2, 14)))
            cipher.updateAAD(associatedData)
            val json =
                JSONObject(
                    String(cipher.doFinal(bytes.copyOfRange(14, bytes.size)), Charsets.UTF_8),
                )
            val token = json.get("accessToken") as String
            require(Regex("[A-Za-z0-9_-]{43}").matches(token))
            val hasRefresh = listOf("refreshToken", "refreshExpiresAt", "sessionId").any(json::has)
            val refresh =
                if (hasRefresh) {
                    val proof = json.get("refreshToken") as String
                    val family = json.get("sessionId") as String
                    require(Regex("[A-Za-z0-9_-]{43}").matches(proof) && proof != token)
                    require(java.util.UUID.fromString(family).toString() == family)
                    RefreshCredentials(
                        proof,
                        Instant.parse(json.get("refreshExpiresAt") as String),
                        family,
                    )
                } else null
            return StoredAuthenticationSession(
                token,
                Instant.parse(json.get("expiresAt") as String),
                json.get("apiIdentity") as String,
                json.opt("accountId") as? String,
                refresh,
            )
        } catch (missing: FileNotFoundException) {
            val hasCommittedFile =
                file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
            if (hasCommittedFile) {
                throw AuthenticationFailure(AccountAccessIssue.SecureStorage)
            }
            deleteRecord()
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val unusableRecord =
                failure is javax.crypto.BadPaddingException ||
                    failure is javax.crypto.IllegalBlockSizeException ||
                    failure is android.security.keystore.KeyPermanentlyInvalidatedException ||
                    failure is org.json.JSONException ||
                    failure is IllegalArgumentException ||
                    failure is ClassCastException ||
                    failure is java.time.DateTimeException
            if (!unusableRecord) {
                throw AuthenticationFailure(AccountAccessIssue.SecureStorage)
            }
            deleteRecord()
            return null
        }
    }

    /**
     * Verifies removal of both committed and recovery files instead of claiming a failed deletion
     * succeeded.
     */
    private fun deleteRecord() {
        file.delete()
        if (hasRecordFiles()) {
            throw AuthenticationFailure(AccountAccessIssue.SecureStorage)
        }
    }

    /** Detects unfinished or legacy atomic files so failed removal cannot claim local sign-out. */
    private fun hasRecordFiles(): Boolean =
        listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new"))
            .any { it.exists() }

    /**
     * Loads or creates only this app ID's encryption key; private key bytes never leave Keystore.
     */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            keyAlias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }
}
