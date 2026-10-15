package com.kesofty.axiapay.domain

/** Local simulation only: atomically reserves funds, refunds known failures and preserves IDs. */
object DemoBulkPolicy {
    /** Records reviewed terms once; repeating an identity cannot reserve funds twice. */
    fun queue(session: DemoSession, batch: BulkBatch): Outcome<SessionChange<BulkBatch>> {
        if (
            batch.recipients.size !in 1..BulkLimits.MAX_RECIPIENTS ||
                batch.title.isBlank() ||
                batch.id.isBlank()
        )
            return Outcome.Failure(DemoError.InvalidRecipient)
        if (batch.recipients.map { it.details.id }.distinct().size != batch.recipients.size)
            return Outcome.Failure(DemoError.RequestConflict)
        if (
            batch.recipients.any {
                !AmountPolicy.isValid(it.details.amountMinorUnits) ||
                    it.status != BulkRecipientStatus.Queued ||
                    it.attempts != 1
            }
        )
            return Outcome.Failure(DemoError.InvalidAmount)
        val existing = session.batches.find { it.id == batch.id }
        if (existing != null) return existingResult(session, existing, batch)
        if (session.offline) return Outcome.Failure(DemoError.OnlineRequired)
        if (session.batches.size >= 20) return Outcome.Failure(DemoError.BulkPreviewLimit)
        if (session.balance < batch.totalMinorUnits)
            return Outcome.Failure(DemoError.InsufficientFunds)
        return Outcome.Success(
            SessionChange(
                session.copy(
                    balance = session.balance - batch.totalMinorUnits,
                    batches = listOf(batch) + session.batches,
                ),
                batch,
            ),
        )
    }

    /**
     * Rejects reuse with changed terms while returning the original attempt for identical input.
     */
    private fun existingResult(
        session: DemoSession,
        existing: BulkBatch,
        requested: BulkBatch,
    ): Outcome<SessionChange<BulkBatch>> {
        val sameTerms =
            existing.title == requested.title &&
                existing.simulateFailure == requested.simulateFailure &&
                existing.recipients.map { it.details } == requested.recipients.map { it.details }
        if (!sameTerms) return Outcome.Failure(DemoError.RequestConflict)
        return Outcome.Success(SessionChange(session, existing))
    }

    /** Reserves only known failures; successful and pending recipients are never retried. */
    fun retry(session: DemoSession, batchId: String): Outcome<SessionChange<BulkBatch>> {
        val batch =
            session.batches.find { it.id == batchId } ?: return Outcome.Failure(DemoError.NotFound)
        if (session.offline) return Outcome.Failure(DemoError.OnlineRequired)
        if (batch.hasPending || batch.failedCount == 0)
            return Outcome.Success(SessionChange(session, batch))
        if (session.balance < batch.failedMinorUnits)
            return Outcome.Failure(DemoError.InsufficientFunds)
        val updated =
            batch.copy(
                recipients =
                    batch.recipients.map { recipient ->
                        if (recipient.status == BulkRecipientStatus.Failed)
                            recipient.copy(
                                status = BulkRecipientStatus.Queued,
                                attempts = recipient.attempts + 1,
                            )
                        else recipient
                    },
            )
        return Outcome.Success(
            SessionChange(
                replace(session.copy(balance = session.balance - batch.failedMinorUnits), updated),
                updated,
            ),
        )
    }

    /** Marks one reserved recipient processing; retained attempts are never incremented. */
    fun beginNext(session: DemoSession, batchId: String): Outcome<SessionChange<BulkRecipient?>> =
        when (val result = beginNextGroup(session, batchId, 1)) {
            is Outcome.Failure -> result
            is Outcome.Success ->
                Outcome.Success(
                    SessionChange(result.value.session, result.value.value.firstOrNull()),
                )
        }

    /** Advances a bounded group together so large demos need no per-row snapshot writes. */
    fun beginNextGroup(
        session: DemoSession,
        batchId: String,
        limit: Int,
    ): Outcome<SessionChange<List<BulkRecipient>>> {
        if (session.offline) return Outcome.Failure(DemoError.OnlineRequired)
        val batch =
            session.batches.find { it.id == batchId } ?: return Outcome.Failure(DemoError.NotFound)
        val pending = batch.recipients.filter { it.status == BulkRecipientStatus.Processing }
        val queued = batch.recipients.filter { it.status == BulkRecipientStatus.Queued }
        val next =
            (pending + queued).take(limit.coerceIn(1, 25)).map {
                it.copy(status = BulkRecipientStatus.Processing)
            }
        if (next.isEmpty()) return Outcome.Success(SessionChange(session, emptyList()))
        val byId = next.associateBy { it.details.id }
        val updated = batch.copy(recipients = batch.recipients.map { byId[it.details.id] ?: it })
        return Outcome.Success(SessionChange(replace(session, updated), next))
    }

    /** Commits one deterministic result; repeating the same attempt cannot pay or refund twice. */
    fun finish(
        session: DemoSession,
        batchId: String,
        recipientId: String,
        expectedAttempt: Int,
    ): Outcome<SessionChange<Unit>> =
        finishGroup(session, batchId, mapOf(recipientId to expectedAttempt))

    /**
     * Atomically records a demo group and releases only known failures, preserving every row ID.
     */
    fun finishGroup(
        session: DemoSession,
        batchId: String,
        attempts: Map<String, Int>,
    ): Outcome<SessionChange<Unit>> {
        if (session.offline) return Outcome.Failure(DemoError.OnlineRequired)
        val batch =
            session.batches.find { it.id == batchId } ?: return Outcome.Failure(DemoError.NotFound)
        if (!batch.recipients.map { it.details.id }.toSet().containsAll(attempts.keys))
            return Outcome.Failure(DemoError.NotFound)
        var released = 0L
        val receipts = mutableListOf<Payment>()
        val rows =
            batch.recipients.mapIndexed { index, recipient ->
                if (
                    recipient.status != BulkRecipientStatus.Processing ||
                        attempts[recipient.details.id] != recipient.attempts
                )
                    return@mapIndexed recipient
                val failed =
                    batch.simulateFailure &&
                        index == batch.recipients.lastIndex &&
                        recipient.attempts == 1
                if (failed) released += recipient.details.amountMinorUnits
                if (!failed) receipts += receipt(batch, recipient.details)
                recipient.copy(
                    status =
                        if (failed) BulkRecipientStatus.Failed else BulkRecipientStatus.Successful,
                )
            }
        val updated = batch.copy(recipients = rows)
        return Outcome.Success(
            SessionChange(
                replace(
                    session.copy(
                        balance = session.balance + released,
                        payments = receipts.asReversed() + session.payments,
                    ),
                    updated,
                ),
                Unit,
            ),
        )
    }

    /** Uses stable batch/recipient identity for each successful simulated payment receipt. */
    private fun receipt(batch: BulkBatch, details: BulkRecipientDetails): Payment =
        Payment(
            "${batch.id}:${details.id}",
            "${details.name} • ${details.provider}",
            batch.title,
            -details.amountMinorUnits,
            PaymentKind.Bulk,
            time = "Just now",
        )

    /** Replaces only the addressed batch while retaining ordering and all other session values. */
    private fun replace(session: DemoSession, batch: BulkBatch): DemoSession =
        session.copy(batches = session.batches.map { if (it.id == batch.id) batch else it })
}
