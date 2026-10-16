package com.kesofty.axiapay.application

/**
 * Normalize declared phone input only; keep ported-network selection separate from number prefixes.
 */
internal fun canonicalPhoneNumber(input: String): String {
    val compact = input.trim().filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
    return when {
        Regex("0[789][0-9]{9}").matches(compact) -> "+234" + compact.drop(1)
        else -> compact
    }
}

/**
 * Lightweight canonical E.164 feedback; the API verifies numbering-plan and product eligibility.
 */
internal fun hasE164Syntax(value: String): Boolean = Regex("\\+[1-9][0-9]{7,14}").matches(value)
