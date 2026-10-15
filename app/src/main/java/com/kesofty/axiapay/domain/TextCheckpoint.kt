package com.kesofty.axiapay.domain

/** Private owner-scoped snapshot storage, separate from backend ledger or payment authority. */
interface TextCheckpoint {
    /** Main-safe read; a missing or damaged existing checkpoint must fail explicitly. */
    suspend fun read(): String?

    /** Atomically replaces content on a worker dispatcher or throws, retaining the old file. */
    suspend fun write(value: String)
}
