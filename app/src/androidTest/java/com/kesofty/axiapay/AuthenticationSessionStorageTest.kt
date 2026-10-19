package com.kesofty.axiapay

import android.content.ContextWrapper
import android.security.NetworkSecurityPolicy
import androidx.test.platform.app.InstrumentationRegistry
import com.kesofty.axiapay.data.auth.KeystoreAuthenticationSessionStore
import com.kesofty.axiapay.domain.auth.StoredAuthenticationSession
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Uses real Android Keystore in isolated storage without overwriting the app's real auth record.
 */
class AuthenticationSessionStorageTest {
    /**
     * A new adapter can decrypt committed records; ciphertext and corruption never become plaintext
     * fallback.
     */
    @Test
    fun encryptedSessionsRestoreAndCorruptionRequiresNewSignin() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory =
            File(base.cacheDir, "auth-storage-test-" + UUID.randomUUID()).apply { mkdirs() }
        val isolated =
            object : ContextWrapper(base) {
                override fun getNoBackupFilesDir(): File = directory

                override fun getPackageName(): String = base.packageName + ".isolatedsessiontest"
            }
        val record =
            StoredAuthenticationSession(
                "z".repeat(43),
                Instant.parse("2026-10-17T12:00:00Z"),
                "https://api.example.test/v1/",
                "123e4567-e89b-42d3-a456-426614174000",
            )
        try {
            val first = KeystoreAuthenticationSessionStore(isolated, Dispatchers.IO)
            first.write(record)
            val ciphertext = File(directory, "authentication/session.v1").readBytes()
            val bytesAsText = String(ciphertext, Charsets.ISO_8859_1)
            assertFalse(bytesAsText.contains(record.accessToken))
            assertFalse(bytesAsText.contains(record.apiIdentity))
            val restored = KeystoreAuthenticationSessionStore(isolated, Dispatchers.IO)
            assertEquals(record, restored.read())
            val newer = record.copy(accessToken = "y".repeat(43))
            restored.write(newer)
            first.clear(record)
            assertEquals(newer, restored.read())
            restored.write(record)
            File(directory, "authentication/session.v1")
                .writeBytes(
                    ciphertext.apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() },
                )
            assertNull(restored.read())
            assertFalse(File(directory, "authentication/session.v1").exists())
            restored.write(record)
            restored.clear(record)
            assertNull(first.read())
            first.write(record)
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(isolated.packageName + ".authentication.v1")
            }
            assertNull(restored.read())
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(isolated.packageName + ".authentication.v1")
            }
        }
    }

    /** The compiled environment permits only local dev HTTP and keeps public endpoints TLS-only. */
    @Test
    fun networkPolicyDoesNotPermitPublicCleartext() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted("public.example.test"))
        assertEquals(BuildConfig.FLAVOR == "dev", policy.isCleartextTrafficPermitted("10.0.2.2"))
    }
}
