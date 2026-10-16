package com.kesofty.axiapay.application.auth

import com.kesofty.axiapay.application.*
import com.kesofty.axiapay.domain.*
import com.kesofty.axiapay.domain.auth.*

/** Preserves all existing demo policies without calling the network or accessing real tokens. */
class DemoAccountAccess(
    private val prepare: PrepareDemoAccess,
    private val complete: CompleteDemoAccess,
    private val resend: ResendDemoAccessCode,
) : AccountAccess {
    override val mode = AccountAccessMode.Demo

    /** Delegates input feedback and routing to the preserved demo application policy. */
    override suspend fun begin(input: AccountAccessInput): AccountAccessResult =
        mapResult(
            prepare(
                DemoAccessInput(
                    input.contactType,
                    input.contact,
                    input.method,
                    input.password,
                    input.name,
                    input.isCreatingAccount,
                ),
            ),
        )

    /** Completes only the simulation; no identity or bearer token is produced. */
    override suspend fun verify(
        challenge: AuthenticationChallenge?,
        code: String,
    ): AccountAccessResult = mapResult(complete(code))

    /** Resets the existing demo for its destination without storing a signup password. */
    override suspend fun resend(
        challenge: AuthenticationChallenge?,
        input: AccountAccessInput,
    ): AccountAccessResult = mapResult(resend(input.contactType, input.contact))

    /** Reports no real session because the demo has no credential persistence. */
    override suspend fun restore(): AccountAccessResult = AccountAccessResult.SignedOut

    /** Reports no real session without accessing network or credential storage. */
    override suspend fun signOut(): AccountAccessResult = AccountAccessResult.SignedOut

    /** Keeps simulated completion distinct from authenticated server sessions. */
    private fun mapResult(result: DemoAccessResult): AccountAccessResult =
        when (result) {
            DemoAccessResult.CodeRequired -> AccountAccessResult.CodeRequired()
            DemoAccessResult.PreviewComplete -> AccountAccessResult.DemoComplete
            DemoAccessResult.InvalidPhone -> AccountAccessResult.Rejected(AccountAccessIssue.Phone)
            DemoAccessResult.InvalidEmail -> AccountAccessResult.Rejected(AccountAccessIssue.Email)
            DemoAccessResult.InvalidName -> AccountAccessResult.Rejected(AccountAccessIssue.Name)
            DemoAccessResult.InvalidPassword ->
                AccountAccessResult.Rejected(AccountAccessIssue.Password)
            DemoAccessResult.InvalidCode -> AccountAccessResult.Rejected(AccountAccessIssue.Code)
        }
}
