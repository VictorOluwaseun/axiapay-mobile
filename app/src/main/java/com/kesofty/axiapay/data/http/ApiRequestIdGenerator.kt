package com.kesofty.axiapay.data.http

import java.time.Clock
import java.util.UUID

/**
 * Generate a fresh diagnostic reference per HTTP call using public device labels, never device IDs.
 */
class ApiRequestIdGenerator(
    manufacturer: String = "Android",
    model: String = "Unknown",
    private val clock: Clock = Clock.systemUTC(),
    private val nextUuid: () -> UUID = UUID::randomUUID,
) {
    private val prefix = "${devicePart(manufacturer, "Android")}-${devicePart(model, "Unknown")}"

    /**
     * Separate every exchange, including read-only revalidation and explicit retries of a payment.
     */
    fun next(): String = "$prefix-${clock.millis()}-${nextUuid()}"

    /**
     * Normalize public build labels into bounded ASCII header parts without changing stored device
     * state.
     */
    private fun devicePart(value: String, fallback: String): String {
        if (value.isBlank() || value.equals("unknown", ignoreCase = true)) {
            return fallback
        }
        return value.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(24).trim('.', '_', '-').ifEmpty {
            fallback
        }
    }
}

/** Share the API's bounded diagnostic format for echoed references and redacted logs only. */
internal fun isValidApiRequestId(value: String?): Boolean =
    value != null && Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(value)
