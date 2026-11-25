package com.kesofty.axiapay.data.purchases

import com.kesofty.axiapay.data.purchases.local.PurchaseCatalogueLocalDataSource
import com.kesofty.axiapay.data.purchases.remote.PurchaseCatalogueRemoteDataSource
import com.kesofty.axiapay.domain.auth.AuthenticationSessionStore
import com.kesofty.axiapay.domain.auth.StoredAuthenticationSession
import com.kesofty.axiapay.domain.purchases.*
import java.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serves saved catalogues and serializes refreshes for the account that requested them. */
class PurchaseCatalogueRepositoryImpl(
    private val remote: PurchaseCatalogueRemoteDataSource,
    private val local: PurchaseCatalogueLocalDataSource,
    private val sessions: AuthenticationSessionStore,
    private val clock: Clock,
) : PurchaseCatalogueRepository {
    private val refreshLock = Mutex()

    /** Read the last committed catalogue for this scope, including a valid empty catalogue. */
    override suspend fun read(
        owner: PurchaseOwner,
        currency: String,
        service: PurchaseService,
    ): PurchaseCatalogue? = local.read(owner, currency, service)

    /** A failed or interrupted download leaves the last complete catalogue available offline. */
    override suspend fun refresh(
        owner: PurchaseOwner,
        accessToken: String,
        currency: String,
        service: PurchaseService,
    ): PurchaseCatalogue = refreshLock.withLock {
        val session = sessions.read() ?: throw PurchaseFailure(PurchaseIssue.SignInRequired)
        requireSession(owner, session)
        val products = remote.products(accessToken, currency, service)
        currentCoroutineContext().ensureActive()
        local.replace(owner, currency, service, products) { requireSession(owner, session) }
    }

    /**
     * A logout/account switch invalidates late refreshes before they can recreate deleted account
     * data.
     */
    private suspend fun requireSession(
        owner: PurchaseOwner,
        captured: StoredAuthenticationSession,
    ) {
        currentCoroutineContext().ensureActive()
        val current = sessions.read()
        if (
            owner.apiIdentity != remote.apiIdentity ||
                owner.apiIdentity != captured.apiIdentity ||
                owner.accountId != captured.accountId ||
                !captured.belongsToSameSession(current) ||
                current?.isUsableAt(clock.instant()) != true
        ) {
            throw PurchaseFailure(PurchaseIssue.SessionChanged)
        }
    }
}
