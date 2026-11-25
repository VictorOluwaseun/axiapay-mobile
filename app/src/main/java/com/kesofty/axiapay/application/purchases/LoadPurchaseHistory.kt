package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*
import java.util.Collections

/** Loads current payment observations for the signed-in account’s recent journal entries. */
class LoadPurchaseHistory(
    private val sessionAccess: PurchaseSessionAccess,
    private val purchases: PurchaseRepository,
    private val requests: PurchaseRequestStore,
) {
    /** Return up to 50 entries, newest first, using one captured session for the entire read. */
    suspend operator fun invoke(accountId: String): List<PurchaseProgress> =
        sessionAccess.withAccount(accountId) { account ->
            val recent = requests.read(account.owner).sortedByDescending { it.createdAt }.take(50)
            Collections.unmodifiableList(
                recent.map { purchases.readProgress(account.session.accessToken, it) },
            )
        }
}
