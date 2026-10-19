package com.kesofty.axiapay.domain.auth

/**
 * Remote authentication port. All operations are main-safe, cancellable and uncached, preserve
 * exact credentials, and throw safe AuthenticationFailure classifications. No automatic retries may
 * replay signup, code redemption or login. The backend authorizes every protected operation.
 */
interface AuthenticationRepository {
    /** Stable normalized API URL used to prevent sending stored credentials to another server. */
    val apiIdentity: String

    /** Requests a fresh purpose-bound code; signup alone may include an exact password. */
    suspend fun requestCode(
        contact: AccountContact,
        purpose: AuthenticationPurpose,
        password: String?,
    ): AuthenticationChallengeTerms

    /** Exchanges exact password characters for an opaque session, without requesting a code. */
    suspend fun loginWithPassword(contact: AccountContact, password: String): AuthenticatedSession

    /** Redeems a challenge once at its original purpose's endpoint; leading zeros are preserved. */
    suspend fun verifyCode(challenge: AuthenticationChallenge, code: String): AuthenticatedSession

    /** Rechecks current expiry, revocation and account status using the bearer token. */
    suspend fun currentAccount(accessToken: String): AccountIdentity

    /** Revokes only this session; an already invalid session is treated as signed out. */
    suspend fun logout(accessToken: String)
}

/**
 * Protected local credential persistence. Implementations perform atomic IO off the main thread,
 * exclude records from backup, and fail closed on corruption/key loss. No cache or payment data
 * belongs here; cached account display does not establish current server authorization.
 */
interface AuthenticationSessionStore {
    /** Reads one encrypted record; unreadable or lost-key records require a fresh sign-in. */
    suspend fun read(): StoredAuthenticationSession?

    /** Atomically persists a new credential before presentation may report successful sign-in. */
    suspend fun write(session: StoredAuthenticationSession)

    /**
     * Removes only the matching record after revocation/invalidation; a newer login must survive.
     */
    suspend fun clear(expected: StoredAuthenticationSession)
}
