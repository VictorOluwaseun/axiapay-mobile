package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*
import com.kesofty.axiapay.wallet.domain.WalletRepository
import com.kesofty.axiapay.wallet.domain.WalletSummary
import java.time.Clock

/** Requests a quote for the signed-in wallet and assigns one request key to the reviewed terms. */
class ReviewPurchase(
    private val sessionAccess: PurchaseSessionAccess,
    private val wallets: WalletRepository,
    private val purchases: PurchaseRepository,
    private val clock: Clock,
    private val newRequestId: () -> String,
) {
    /**
     * Return the server quote only when it matches the prepared selection, recipient and wallet.
     */
    suspend operator fun invoke(accountId: String, input: PurchaseQuoteInput): PurchaseReview =
        sessionAccess.withAccount(accountId) { access ->
            requireCurrentMeter(access.owner, input)
            val token = access.session.accessToken
            val wallet = wallets.currentWallet(token, "NGN")
            val quote = purchases.quote(token, wallet.id, input)
            if (!quote.matches(input, wallet)) throw PurchaseFailure(PurchaseIssue.InvalidResponse)
            PurchaseReview(
                request =
                    PurchaseRequest(
                        owner = access.owner,
                        requestKey = newRequestId(),
                        quoteId = quote.id,
                        createdAt = clock.instant(),
                        service = input.service,
                    ),
                quote = quote,
            )
        }

    /**
     * Fixed-price selections take their price from the quote. A supplied amount must match exactly.
     */
    private fun PurchaseQuote.matches(input: PurchaseQuoteInput, wallet: WalletSummary): Boolean =
        service == input.service &&
            walletId == wallet.id &&
            productId == input.productId &&
            recipientIdentifier == input.recipientIdentifier &&
            currency == wallet.currency &&
            meterValidation == input.verifiedMeter?.validation &&
            (service != PurchaseService.Electricity ||
                contactPhoneNumber == input.contactPhoneNumber) &&
            (input.amountMinorUnits == null || principalMinorUnits == input.amountMinorUnits)

    /** A validation capability belongs to one owner/API and must be fresh at quote creation. */
    private fun requireCurrentMeter(
        owner: PurchaseOwner,
        input: PurchaseQuoteInput,
    ) {
        if (input.service != PurchaseService.Electricity) {
            return
        }
        val verified =
            input.verifiedMeter ?: throw PurchaseFailure(PurchaseIssue.MeterVerificationRequired)
        if (verified.owner != owner) {
            throw PurchaseFailure(PurchaseIssue.SessionChanged)
        }
        if (verified.validation.expiresAt <= clock.instant()) {
            throw PurchaseFailure(PurchaseIssue.MeterVerificationExpired)
        }
    }
}
