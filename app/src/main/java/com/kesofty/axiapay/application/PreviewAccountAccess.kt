package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.DemoAccessRepository
import com.kesofty.axiapay.domain.DemoAccessResult

/** Validates demo form inputs before preparing the preview; it never requests a real OTP. */
class PrepareDemoAccess(private val repository: DemoAccessRepository) {
    /**
     * Accepts local or +234 Nigerian numbers and requires a name only on the create-account
     * preview.
     */
    suspend operator fun invoke(
        phoneNumber: String,
        name: String,
        isCreatingAccount: Boolean,
    ): DemoAccessResult {
        val normalized = phoneNumber.filterNot { it == ' ' || it == '-' }
        if (!Regex("(?:0[789][0-9]{9}|\\+234[789][0-9]{9})").matches(normalized))
            return DemoAccessResult.InvalidPhone
        if (isCreatingAccount && name.trim().length < 2) return DemoAccessResult.InvalidName
        return repository.preparePreview(normalized)
    }
}

/** Checks code shape for the demo UI; any six ASCII digits are accepted and none are persisted. */
class CompleteDemoAccess(private val repository: DemoAccessRepository) {
    /**
     * Delegates only a structurally valid preview code; no ownership or authorization is
     * established.
     */
    suspend operator fun invoke(code: String): DemoAccessResult {
        if (!Regex("[0-9]{6}").matches(code)) return DemoAccessResult.InvalidCode
        return repository.completePreview(code)
    }
}
