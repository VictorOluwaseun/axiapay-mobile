package com.kesofty.axiapay

import com.kesofty.axiapay.data.http.ApiRequestIdGenerator
import com.kesofty.axiapay.data.http.isValidApiRequestId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Verify public device labels, safe header bounds and per-exchange uniqueness at a fixed time. */
class ApiRequestIdGeneratorTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(1700000000000), ZoneOffset.UTC)

    /**
     * Use the actual manufacturer and model separately, without a slash or a hardcoded Samsung
     * label.
     */
    @Test
    fun formatsManufacturerModelTimestampAndUuid() {
        val uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val generator = ApiRequestIdGenerator("samsung", "SM-A556E", clock) { uuid }
        assertEquals("samsung-SM-A556E-1700000000000-$uuid", generator.next())
    }

    /**
     * Unusual build strings cannot create header injection, oversized references or device-specific
     * fallback IDs.
     */
    @Test
    fun normalizesAndBoundsUnknownOrUnsafeDeviceLabels() {
        for ((manufacturer, model) in
            listOf(
                "Vendor Name\r\n" to "Model / one",
                "😀" to "unknown",
                "" to "",
                "a".repeat(300) to "b".repeat(300),
            )) {
            val id = ApiRequestIdGenerator(manufacturer, model, clock).next()
            assertTrue(isValidApiRequestId(id))
            assertTrue(id.length <= 128)
            assertFalse(id.contains('/'))
            assertFalse(id.contains('\n'))
        }
        assertTrue(
            ApiRequestIdGenerator("unknown", "", clock).next().startsWith("Android-Unknown-"),
        )
    }

    /**
     * Several callers sharing one generator still receive distinct IDs within the same millisecond.
     */
    @Test
    fun concurrentRequestsAtTheSameTimeHaveDifferentIds() = runBlocking {
        val generator = ApiRequestIdGenerator("Google", "Pixel 9", clock)
        val ids = (1..100).map { async(Dispatchers.Default) { generator.next() } }.awaitAll()
        assertEquals(100, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("Google-Pixel-9-1700000000000-") })
    }
}
