package com.kesofty.axiapay

import com.kesofty.axiapay.data.DefaultNetworkTracker
import org.junit.Assert.*
import org.junit.Test

class DefaultNetworkTrackerTest {
    @Test
    fun `wifi with internet capability stays offline until validation and goes offline if validation is lost`() {
        val tracker = DefaultNetworkTracker()
        tracker.available(1)
        assertEquals(false, tracker.capabilities(1, internet = true, validated = false))
        assertEquals(true, tracker.capabilities(1, internet = true, validated = true))
        assertEquals(false, tracker.capabilities(1, internet = true, validated = false))
        assertEquals(false, tracker.capabilities(1, internet = false, validated = true))
    }

    @Test
    fun `late wifi loss cannot mark the new validated cellular default offline`() {
        val tracker = DefaultNetworkTracker()
        tracker.available(1)
        assertEquals(true, tracker.capabilities(1, true, true))
        tracker.available(2)
        assertEquals(true, tracker.capabilities(2, true, true))
        assertNull(tracker.lost(1))
        assertNull(tracker.capabilities(1, true, false))
        assertNull(tracker.blocked(1, true))
        assertEquals(false, tracker.lost(2))
    }

    @Test
    fun `blocked default stays offline through capability updates and recovers when unblocked`() {
        val tracker = DefaultNetworkTracker()
        tracker.available(1)
        assertEquals(true, tracker.capabilities(1, true, true))
        assertEquals(false, tracker.blocked(1, true))
        assertEquals(false, tracker.capabilities(1, true, true))
        assertEquals(true, tracker.blocked(1, false))
    }

    @Test
    fun `a default lost before initial available callback clears the initial snapshot`() {
        assertEquals(false, DefaultNetworkTracker().lost(1))
    }
}
