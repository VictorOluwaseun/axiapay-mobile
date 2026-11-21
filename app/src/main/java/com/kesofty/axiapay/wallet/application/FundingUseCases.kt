package com.kesofty.axiapay.wallet.application

/** Funding operations available to the signed-in account's funding screen. */
data class FundingUseCases(
    val loadFundingContext: LoadFundingContext,
    val prepareFundingRequest: PrepareFundingRequest,
    val createFundingRequest: CreateFundingRequest,
    val getFundingRequest: GetFundingRequest,
    val submitFundingReference: SubmitFundingReference,
    val reconcileFundingRequest: ReconcileFundingRequest,
)
