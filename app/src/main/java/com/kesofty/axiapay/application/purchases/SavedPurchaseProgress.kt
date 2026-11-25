package com.kesofty.axiapay.application.purchases

import com.kesofty.axiapay.domain.purchases.*

/** Check journal ownership and exact request identity before a status lookup or reconciliation. */
internal suspend fun PurchaseRequestStore.requireSaved(
    owner: PurchaseOwner,
    request: PurchaseRequest,
) {
    request.requireOwnedBy(owner)
    if (request !in read(owner)) throw PurchaseFailure(PurchaseIssue.NotFound)
}

/** Read the observed payment or its original quote when the server has no payment for this key. */
internal suspend fun PurchaseRepository.readProgress(
    accessToken: String,
    request: PurchaseRequest,
): PurchaseProgress {
    val payment = findByRequestKey(accessToken, request.requestKey)
    if (payment != null) return PurchaseProgress(request, payment)
    return PurchaseProgress(
        request,
        null,
        readOriginalQuote(accessToken, request),
        PurchaseIssue.NotFound,
    )
}

/**
 * A missing quote leaves the request unconfirmed. Reject terms for a different quote or service.
 */
private suspend fun PurchaseRepository.readOriginalQuote(
    accessToken: String,
    request: PurchaseRequest,
): PurchaseQuote? {
    val quote =
        try {
            getQuote(accessToken, request.quoteId)
        } catch (failure: PurchaseFailure) {
            if (failure.issue == PurchaseIssue.NotFound) return null
            throw failure
        }
    if (quote.id != request.quoteId || quote.service != request.service) {
        throw PurchaseFailure(PurchaseIssue.InvalidResponse)
    }
    return quote
}
