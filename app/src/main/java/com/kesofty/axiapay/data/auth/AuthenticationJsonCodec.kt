package com.kesofty.axiapay.data.auth

import com.kesofty.axiapay.domain.AuthContactType
import com.kesofty.axiapay.domain.auth.*
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

/**
 * Strictly maps the published auth HTTP contract; JSON and transport fields stay in this adapter.
 */
internal class AuthenticationJsonCodec(private val clock: Clock) {
    /** Encodes contact shape only; UI names and unrelated credentials never enter this payload. */
    fun contact(contact: AccountContact): JSONObject =
        JSONObject()
            .put("kind", if (contact.kind == AuthContactType.Phone) "phone" else "email")
            .put("value", contact.value)

    /** Rejects incomplete or mistyped challenges instead of accepting implicit JSON conversion. */
    fun challenge(json: JSONObject): AuthenticationChallengeTerms {
        uuid(json, "challengeId")
        Instant.parse(string(json, "expiresAt"))
        return AuthenticationChallengeTerms(
            string(json, "challengeId"),
            integer(json, "expiresInSeconds", 0..600),
            integer(json, "resendAfterSeconds", 1..86400),
        )
    }

    /** Validates the opaque bearer contract and bounds locally estimated expiry conservatively. */
    fun session(json: JSONObject): AuthenticatedSession {
        require(string(json, "tokenType") == "Bearer")
        val token = string(json, "accessToken")
        require(Regex("[A-Za-z0-9_-]{43}").matches(token))
        val expires = integer(json, "expiresInSeconds", 1..86400)
        return AuthenticatedSession(
            token,
            clock.instant().plusSeconds(expires),
            account(json.getJSONObject("account")),
        )
    }

    /** Maps only a verified public identity; credential or storage metadata is never exposed. */
    fun account(json: JSONObject): AccountIdentity {
        val id = uuid(json, "id")
        val contact = json.getJSONObject("contact")
        val kind =
            when (string(contact, "kind")) {
                "phone" -> AuthContactType.Phone
                "email" -> AuthContactType.Email
                else -> error("Unsupported contact kind")
            }
        val value = string(contact, "value")
        require(value.isNotBlank() && value.length <= 254)
        return AccountIdentity(
            id,
            AccountContact(kind, value),
            Instant.parse(string(json, "verifiedAt")),
        )
    }

    /** Reads actual strings, preserving passwords/codes and rejecting implicit coercion. */
    private fun string(json: JSONObject, key: String): String =
        json.get(key) as? String ?: error("Expected string")

    /** Validates canonical UUIDs before accepting server identities or challenge references. */
    private fun uuid(json: JSONObject, key: String): String {
        val value = string(json, key)
        require(UUID.fromString(value).toString() == value.lowercase(java.util.Locale.ROOT))
        return value
    }

    /** Accepts bounded JSON integers only; fractional or string timing never becomes a timer. */
    private fun integer(json: JSONObject, key: String, range: IntRange): Long {
        val value = json.get(key)
        require(value is Int || value is Long)
        val number = (value as Number).toLong()
        require(number in range.first.toLong()..range.last.toLong())
        return number
    }
}
