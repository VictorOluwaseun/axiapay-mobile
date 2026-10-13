package com.kesofty.axiapay.data

/** Default-network handovers can leave late callbacks for the previous network. Ignore them. */
internal class DefaultNetworkTracker {
    private var current: Long? = null
    private var validated = false
    private var blocked = false

    fun available(handle: Long) {
        current = handle
        validated = false
        blocked = false
    }

    fun capabilities(handle: Long, internet: Boolean, validated: Boolean): Boolean? {
        if (handle != current) return null
        this.validated = internet && validated
        return this.validated && !blocked
    }

    fun blocked(handle: Long, blocked: Boolean): Boolean? {
        if (handle != current) return null
        this.blocked = blocked
        return validated && !blocked
    }

    fun lost(handle: Long): Boolean? {
        if (current != null && current != handle) return null
        current = null
        validated = false
        blocked = false
        return false
    }
}
