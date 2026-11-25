package com.kesofty.axiapay.data.purchases.remote

import com.kesofty.axiapay.data.http.ApiFailureKind
import com.kesofty.axiapay.data.http.ApiRequestFailure
import com.kesofty.axiapay.domain.purchases.PurchaseFailure
import com.kesofty.axiapay.domain.purchases.PurchaseIssue
import kotlinx.coroutines.CancellationException

/** Redact upstream messages and preserve cancellation, including during error-body reads. */
internal inline fun <T> mapPurchaseResponse(operation: () -> T): T {
    try {
        return operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: PurchaseFailure) {
        throw failure
    } catch (failure: ApiRequestFailure) {
        val issue =
            when {
                failure.kind == ApiFailureKind.Configuration -> PurchaseIssue.Configuration
                failure.kind == ApiFailureKind.Network -> PurchaseIssue.Network
                failure.kind == ApiFailureKind.Storage -> PurchaseIssue.Storage
                failure.kind == ApiFailureKind.InvalidResponse -> PurchaseIssue.InvalidResponse
                failure.status == 401 -> PurchaseIssue.SignInRequired
                failure.code == "ACCOUNT_INVALID" -> PurchaseIssue.InvalidMeter
                failure.code == "ACCOUNT_UNCONFIRMED" -> PurchaseIssue.MeterUnconfirmed
                failure.code == "ACCOUNT_VALIDATION_EXPIRED" ->
                    PurchaseIssue.MeterVerificationExpired
                failure.code == "ACCOUNT_VALIDATION_NOT_FOUND" ->
                    PurchaseIssue.MeterVerificationRequired
                failure.code == "QUOTE_EXPIRED" -> PurchaseIssue.QuoteExpired
                failure.code == "INSUFFICIENT_FUNDS" -> PurchaseIssue.InsufficientFunds
                failure.code == "WALLET_RESTRICTED" -> PurchaseIssue.WalletRestricted
                failure.code in setOf("PAYMENT_CONFLICT", "IDEMPOTENCY_CONFLICT") ->
                    PurchaseIssue.PaymentConflict
                failure.code == "FEATURE_DISABLED" -> PurchaseIssue.FeatureDisabled
                failure.code == "PRODUCT_UNAVAILABLE" -> PurchaseIssue.ProductUnavailable
                failure.code == "INVALID_PAYMENT" -> PurchaseIssue.InvalidPayment
                failure.status == 404 -> PurchaseIssue.NotFound
                else -> PurchaseIssue.Unavailable
            }
        throw PurchaseFailure(issue, failure)
    } catch (failure: Exception) {
        throw PurchaseFailure(PurchaseIssue.InvalidResponse, failure)
    }
}
