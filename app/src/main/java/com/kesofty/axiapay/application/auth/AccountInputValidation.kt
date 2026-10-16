package com.kesofty.axiapay.application.auth

import com.kesofty.axiapay.application.canonicalPhoneNumber
import com.kesofty.axiapay.application.hasE164Syntax
import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.AuthMethod
import com.kesofty.axiapay.domain.auth.*
import java.util.Locale

/**
 * Normalizes Nigerian local numbers to E.164 and preserves international numbers and email aliases.
 */
internal fun normalizedAccountContact(input: AccountAccessInput): AccountContact {
    val value = input.contact.trim()
    if (input.contactType == AuthContactType.Email) {
        return AccountContact(input.contactType, value.lowercase(Locale.ROOT))
    }
    return AccountContact(input.contactType, canonicalPhoneNumber(value))
}

/** Lightweight feedback mirrors the HTTP boundary; the server still validates and authenticates. */
internal fun accountInputIssue(
    input: AccountAccessInput,
    contact: AccountContact,
): AccountAccessIssue? {
    val validContact =
        when (contact.kind) {
            AuthContactType.Phone -> hasE164Syntax(contact.value)
            AuthContactType.Email ->
                contact.value.length <= 254 &&
                    Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(contact.value)
        }
    if (!validContact) {
        return if (contact.kind == AuthContactType.Phone) AccountAccessIssue.Phone
        else AccountAccessIssue.Email
    }
    if (input.method != AuthMethod.Password) {
        return null
    }
    val length = input.password.codePointCount(0, input.password.length)
    val minimum = if (input.isCreatingAccount) 15 else 1
    return if (length !in minimum..128) AccountAccessIssue.Password else null
}
