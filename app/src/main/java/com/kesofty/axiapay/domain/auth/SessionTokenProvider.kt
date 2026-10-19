package com.kesofty.axiapay.domain.auth

/**
 * Main-safe session credentials for one protected request; renewal never crosses an API or login
 * boundary.
 */
interface SessionTokenProvider {
    /**
     * Coalesce rotation and commit its new credentials before allowing a rejected request to retry
     * once.
     */
    suspend fun accessToken(
        apiIdentity: String,
        expectedToken: String,
        forceRenewal: Boolean = false,
    ): String

    /** Remove only the exact rejected credential; a later rotation or login must survive. */
    suspend fun rejected(apiIdentity: String, accessToken: String)
}
