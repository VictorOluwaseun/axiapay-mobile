package com.kesofty.axiapay.application.auth

import com.kesofty.axiapay.domain.auth.*

/**
 * Application operations for account entry and session recovery. Presentation coordinates these use
 * cases and renders results; only the composition boundary selects demo or server workflows.
 */
interface AccountAccess {
    /** Determines copy and available controls, not permission to perform financial operations. */
    val mode: AccountAccessMode

    /** Validates a captured form and explicitly requests a challenge or password login. */
    suspend fun begin(input: AccountAccessInput): AccountAccessResult

    /** Verifies one purpose-bound challenge; a missing challenge is valid only in demo. */
    suspend fun verify(challenge: AuthenticationChallenge?, code: String): AccountAccessResult

    /** Requests replacement proof; password signup must return to the form for password entry. */
    suspend fun resend(
        challenge: AuthenticationChallenge?,
        input: AccountAccessInput,
    ): AccountAccessResult

    /** Read saved display records bound to an unexpired protected session, without network IO. */
    suspend fun cached(): AccountAccessResult.SignedIn? = null

    /** Revalidates a protected session; a connectivity failure must not become demo access. */
    suspend fun restore(): AccountAccessResult

    /** Revokes and removes a session; failures remain retryable without claiming sign-out. */
    suspend fun signOut(): AccountAccessResult
}
