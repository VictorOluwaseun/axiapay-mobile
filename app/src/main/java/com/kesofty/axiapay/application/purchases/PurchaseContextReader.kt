package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.wallet.domain.WalletSummary
import java.util.Collections

/** Assembles display terms and saved recovery references for one account and service. */
class PurchaseContextReader(
    private val catalogues: PurchaseCatalogueRepository,
    private val requests: PurchaseRequestStore,
) {
    /** Read the latest complete catalogue after the wallet and catalogue operations finish. */
    internal suspend fun read(
        owner: PurchaseOwner,
        service: PurchaseService,
        wallet: WalletSummary?,
        isCached: Boolean = true,
        issue: PurchaseIssue? = null,
    ): PurchaseContext {
        val catalogue = catalogues.read(owner, "NGN", service)
        return PurchaseContext(
            owner = owner,
            wallet = wallet,
            products = Collections.unmodifiableList(catalogue?.products.orEmpty().toList()),
            requests =
                Collections.unmodifiableList(requests.read(owner).filter { it.service == service }),
            isCached = isCached,
            issue = issue,
        )
    }
}
