package com.kesofty.axiapay.wallet.application

import com.kesofty.axiapay.wallet.domain.FundingFailure
import com.kesofty.axiapay.wallet.domain.FundingInput
import com.kesofty.axiapay.wallet.domain.FundingIssue
import com.kesofty.axiapay.wallet.domain.FundingOption
import java.math.BigDecimal

/** Converts a customer's draft to the exact terms of one funding request. */
class PrepareFundingRequest {
    /**
     * Validate client entry once and construct exact, immutable request terms for explicit
     * submission.
     */
    operator fun invoke(
        option: FundingOption,
        amount: String,
        email: String,
        key: String,
    ): FundingInput {
        if (!Regex("(?:0|[1-9][0-9]{0,12})(?:\\.[0-9]{1,2})?").matches(amount)) {
            throw FundingFailure(FundingIssue.InvalidInput)
        }
        val amountMinorUnits = BigDecimal(amount).movePointRight(2).toBigIntegerExact()
        if (
            amountMinorUnits < option.minimum ||
                amountMinorUnits > option.maximum ||
                option.currency != "NGN"
        ) {
            throw FundingFailure(FundingIssue.InvalidInput)
        }
        if (!option.bankTransfer && email.isBlank()) {
            throw FundingFailure(FundingIssue.InvalidInput)
        }
        return FundingInput(
            option.id,
            amountMinorUnits,
            email.trim().takeIf { !option.bankTransfer },
            key,
        )
    }
}
