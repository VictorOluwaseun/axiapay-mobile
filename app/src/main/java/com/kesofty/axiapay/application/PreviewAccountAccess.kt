package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*

/**
 * Validates a demo submission and chooses its next step; it never establishes real account access.
 */
class PrepareDemoAccess(private val repository: DemoAccessRepository) {
    /**
     * All signup methods and code sign-in require verification. Password sign-in completes
     * directly. The preview uses an eight-character password minimum. Password whitespace/case is
     * preserved; failures and cancellation propagate to the caller.
     */
    suspend operator fun invoke(input: DemoAccessInput): DemoAccessResult {
        val normalized =
            input.copy(contact = normalizeAuthContact(input.contactType, input.contact))
        validateAuthContact(normalized.contactType, normalized.contact)?.let {
            return it
        }
        if (normalized.isCreatingAccount && normalized.name.trim().length < 2)
            return DemoAccessResult.InvalidName
        if (
            normalized.method == AuthMethod.Password &&
                (normalized.password.length < 8 || normalized.password.isBlank())
        )
            return DemoAccessResult.InvalidPassword
        // Code flows must not pass a stale password even when called outside the ViewModel.
        val submitted =
            normalized.copy(
                password = if (input.method == AuthMethod.Password) input.password else "",
            )
        repository.preparePreview(submitted)
        if (!submitted.isCreatingAccount && submitted.method == AuthMethod.Password)
            return DemoAccessResult.PreviewComplete
        return DemoAccessResult.CodeRequired
    }
}

/**
 * Checks demo code shape; any six ASCII digits complete the preview without authenticating a
 * person.
 */
class CompleteDemoAccess(private val repository: DemoAccessRepository) {
    /**
     * Preserves leading zeros, rejects malformed codes before delegation, and propagates failures.
     */
    suspend operator fun invoke(code: String): DemoAccessResult {
        if (!Regex("[0-9]{6}").matches(code)) return DemoAccessResult.InvalidCode
        repository.completePreview(code)
        return DemoAccessResult.PreviewComplete
    }
}

/** Repeats an existing code preview without retaining or re-requesting a signup password. */
class ResendDemoAccessCode(private val repository: DemoAccessRepository) {
    /**
     * Validates the selected destination and resets only the simulation; no message is delivered.
     */
    suspend operator fun invoke(contactType: AuthContactType, contact: String): DemoAccessResult {
        val normalized = normalizeAuthContact(contactType, contact)
        validateAuthContact(contactType, normalized)?.let {
            return it
        }
        repository.resendPreview(contactType, normalized)
        return DemoAccessResult.CodeRequired
    }
}

/** Removes phone separators or surrounding email whitespace without changing email case. */
private fun normalizeAuthContact(contactType: AuthContactType, contact: String): String =
    when (contactType) {
        AuthContactType.Phone -> contact.trim().filterNot { it == ' ' || it == '-' }
        AuthContactType.Email -> contact.trim()
    }

/**
 * Provides lightweight demo input feedback; the future backend owns authoritative contact
 * validation.
 */
private fun validateAuthContact(contactType: AuthContactType, contact: String): DemoAccessResult? =
    when (contactType) {
        AuthContactType.Phone ->
            if (Regex("(?:0[789][0-9]{9}|\\+234[789][0-9]{9})").matches(contact)) null
            else DemoAccessResult.InvalidPhone
        AuthContactType.Email ->
            if (hasDemoEmailShape(contact)) null else DemoAccessResult.InvalidEmail
    }

/**
 * Accepts a practical email shape while rejecting empty parts, whitespace and malformed domains.
 */
private fun hasDemoEmailShape(contact: String): Boolean {
    if (contact.length > 254 || contact.count { it == '@' } != 1) return false
    val localPart = contact.substringBefore('@')
    val domain = contact.substringAfter('@')
    if (
        localPart.isEmpty() ||
            localPart.length > 64 ||
            localPart.startsWith('.') ||
            localPart.endsWith('.') ||
            ".." in localPart
    )
        return false
    if (!Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+").matches(localPart)) return false
    val labels = domain.split('.')
    if (labels.size < 2) return false
    return labels.all { label ->
        label.length in 1..63 && Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?").matches(label)
    }
}
