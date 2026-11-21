package com.kesofty.axiapay.wallet.data

import com.kesofty.axiapay.data.http.ApiFailureKind
import com.kesofty.axiapay.data.http.ApiRequestFailure
import com.kesofty.axiapay.wallet.data.remote.FundingRemoteDataSource
import com.kesofty.axiapay.wallet.data.remote.toFundingContext
import com.kesofty.axiapay.wallet.data.remote.toFundingRequest
import com.kesofty.axiapay.wallet.domain.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Returns server-owned funding records after correlating them to the requested wallet or request.
 */
class FundingRepositoryImpl(private val remote: FundingRemoteDataSource) : FundingRepository {
    override val apiIdentity: String
        get() = remote.apiIdentity

    /** Return options and request history for this wallet only. */
    override suspend fun context(token: String, walletId: String, offset: Int): FundingContext =
        guarded {
            remote.context(token, walletId, offset).toFundingContext(walletId)
        }

    /** Accept only a server response for the wallet, option and amount actually submitted. */
    override suspend fun create(
        token: String,
        walletId: String,
        input: FundingInput,
    ): FundingRequest = guarded {
        val request = remote.create(token, walletId, input).toFundingRequest()
        require(
            request.walletId == walletId &&
                request.optionId == input.optionId &&
                request.amountMinorUnits == input.amountMinorUnits,
        )
        request
    }

    /** Read previously saved progress. */
    override suspend fun get(token: String, requestId: String): FundingRequest =
        matchingRequest(requestId) { remote.get(token, requestId) }

    /** Send evidence for this request and return the server's status. */
    override suspend fun submitReference(
        token: String,
        requestId: String,
        reference: String,
    ): FundingRequest =
        matchingRequest(requestId) { remote.submitReference(token, requestId, reference) }

    /** Keep explicit reconciliation bound to the selected request. */
    override suspend fun reconcile(token: String, requestId: String): FundingRequest =
        matchingRequest(requestId) { remote.reconcile(token, requestId) }

    /** Reject a response belonging to another funding request. */
    private suspend fun matchingRequest(
        requestId: String,
        fetch: suspend () -> JSONObject,
    ): FundingRequest = guarded { fetch().toFundingRequest().also { require(it.id == requestId) } }

    /** Preserve cancellation and map only bounded transport metadata. */
    private suspend fun <T> guarded(operation: suspend () -> T): T {
        try {
            return operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiRequestFailure) {
            val issue =
                when {
                    failure.kind == ApiFailureKind.Network -> FundingIssue.Network
                    failure.status == 401 -> FundingIssue.SignInRequired
                    failure.status == 409 -> FundingIssue.Conflict
                    // INVALID_FUNDING can also follow a persisted request and a failed provider
                    // response.
                    failure.status == 400 && failure.code == "INVALID_INPUT" ->
                        FundingIssue.InvalidInput
                    else -> FundingIssue.Unavailable
                }
            throw FundingFailure(issue)
        } catch (_: Exception) {
            throw FundingFailure(FundingIssue.InvalidResponse)
        }
    }
}
