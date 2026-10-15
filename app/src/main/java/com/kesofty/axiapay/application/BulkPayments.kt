package com.kesofty.axiapay.application

import com.kesofty.axiapay.domain.*
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.delay

/** Untrusted form input is validated again when a demo batch is submitted. */
data class BulkRecipientInput(
    val id: String,
    val name: String = "",
    val recipientReference: String = "",
    val provider: String = "MTN",
    val service: BulkService = BulkService.Airtime,
    val amount: String = "1000",
    val product: String = "2 GB",
)

/** One draft has one stable identity across edits, review and duplicate confirmation. */
data class BulkDraft(
    val id: String,
    val title: String = "",
    val recipients: List<BulkRecipientInput> = emptyList(),
    val simulateFailure: Boolean = true,
    val service: BulkService = recipients.firstOrNull()?.service ?: BulkService.Airtime,
)

/** Validation codes are mapped to resources by presentation; no user-facing strings leak inward. */
enum class BulkInputIssue {
    ServiceMismatch,
    EmptyRecipients,
    RecipientLimit,
    RecipientReference,
    Amount,
    Provider,
    DataPlan,
    DuplicateRecipient,
}

/** A row identity allows the UI to focus the relevant input without relying on list order. */
data class BulkValidationIssue(val recipientId: String?, val kind: BulkInputIssue)

/**
 * Prepared terms exist only for valid rows; a batch can proceed only when every issue is resolved.
 */
data class BulkValidation(
    val recipients: List<BulkRecipientDetails>,
    val issues: List<BulkValidationIssue>,
) {
    val valid: Boolean
        get() = issues.isEmpty()

    val totalMinorUnits: Long = recipients.sumOf { it.amountMinorUnits }
}

/** Reuses single-payment validation and requires bounded, unique recipients of one service. */
class PrepareBulkBatch(private val preparePayment: PreparePayment, private val clock: Clock) {
    /** Validates a complete review, preserving leading zeros and exact NGN minor units. */
    fun validate(draft: BulkDraft): BulkValidation {
        val issues = mutableListOf<BulkValidationIssue>()
        if (draft.recipients.any { it.service != draft.service })
            issues += BulkValidationIssue(null, BulkInputIssue.ServiceMismatch)
        if (draft.recipients.isEmpty())
            issues += BulkValidationIssue(null, BulkInputIssue.EmptyRecipients)
        if (draft.recipients.size > BulkLimits.MAX_RECIPIENTS)
            issues += BulkValidationIssue(null, BulkInputIssue.RecipientLimit)
        val duplicates =
            draft.recipients
                .groupBy { recipientIdentity(it) }
                .filterValues { it.size > 1 }
                .values
                .flatten()
                .map { it.id }
                .toSet()
        val prepared =
            draft.recipients.mapNotNull { input ->
                val issue = validateRecipient(input)
                if (issue != null) issues += BulkValidationIssue(input.id, issue)
                if (input.id in duplicates)
                    issues += BulkValidationIssue(input.id, BulkInputIssue.DuplicateRecipient)
                if (issue == null) details(input) else null
            }
        return BulkValidation(prepared, issues)
    }

    /** Validates one editor independently, while batch review also checks duplicate recipients. */
    fun validateRecipient(input: BulkRecipientInput): BulkInputIssue? {
        if (
            input.service == BulkService.TV &&
                BulkServiceCatalog.quote(input.service, input.product) == null
        )
            return BulkInputIssue.DataPlan
        if (
            input.service == BulkService.Electricity &&
                input.product !in BulkServiceCatalog.products(input.service)
        )
            return BulkInputIssue.DataPlan
        return when (preparePayment.validate(paymentInput(input)).error) {
            null -> null
            DemoError.InvalidRecipient -> BulkInputIssue.RecipientReference
            DemoError.InvalidProvider -> BulkInputIssue.Provider
            DemoError.InvalidPlan -> BulkInputIssue.DataPlan
            else -> BulkInputIssue.Amount
        }
    }

    /** Produces the immutable queue request only after all rows and summary fields are valid. */
    operator fun invoke(draft: BulkDraft): Outcome<BulkBatch> {
        val validation = validate(draft)
        if (!validation.valid) return Outcome.Failure(DemoError.InvalidRecipient)
        return Outcome.Success(
            BulkBatch(
                draft.id,
                draft.title.trim().ifBlank { draft.service.name },
                validation.recipients.map { BulkRecipient(it) },
                clock.instant(),
                draft.simulateFailure,
            ),
        )
    }

    /** Uses existing sample plan quotes, never an editable data-package price. */
    private fun paymentInput(input: BulkRecipientInput): PaymentInput =
        PaymentInput(
            type = BulkServiceCatalog.paymentType(input.service),
            provider = input.provider,
            recipient = canonicalReference(input),
            amount = BulkServiceCatalog.quote(input.service, input.product) ?: input.amount,
            dataPlan = input.product,
            date = LocalDate.now(clock),
        )

    /** Converts validated input to provider-neutral reviewed terms. */
    private fun details(input: BulkRecipientInput): BulkRecipientDetails =
        BulkRecipientDetails(
            input.id,
            input.name.trim().ifBlank { canonicalReference(input) },
            canonicalReference(input),
            input.provider,
            input.service,
            requireNotNull(preparePayment.validate(paymentInput(input)).amount),
            if (input.service == BulkService.Airtime) "" else input.product,
        )

    /** Shares canonical duplicate identity with streaming import validation in linear time. */
    fun recipientIdentity(input: BulkRecipientInput): Triple<String, BulkService, String> =
        Triple(canonicalReference(input), input.service, input.provider)

    /** Accepts local or +234 notation without converting account identifiers to numbers. */
    private fun canonicalReference(input: BulkRecipientInput): String {
        if (input.service in listOf(BulkService.Electricity, BulkService.TV))
            return input.recipientReference.trim()
        val compact = input.recipientReference.filterNot(Char::isWhitespace)
        if (compact.startsWith("+234")) return "0" + compact.removePrefix("+234")
        return compact
    }
}

/** Flavor-selected queue action; it reserves demo funds only after explicit confirmation. */
fun interface QueueBulkBatch {
    /** Revalidates all reviewed terms and returns an existing batch for an identical request. */
    suspend operator fun invoke(draft: BulkDraft): Outcome<BulkBatch>
}

/** Flavor-selected recovery action; it never retries successful or uncertain recipients. */
fun interface RetryBulkBatch {
    /** Reserves only known-failed rows after the user reviews their retry total. */
    suspend operator fun invoke(batchId: String): Outcome<BulkBatch>
}

/** Flavor-selected progress action; restoration and reconnection never invoke it implicitly. */
fun interface RunBulkBatch {
    /** Advances pending recipients; cancellation retains saved progress and reserved funds. */
    suspend operator fun invoke(batchId: String): Outcome<Unit>
}

/** Coordinates validation with the same atomic repository used by individual wallet payments. */
class QueueDemoBulkBatch(
    private val repository: SessionRepository,
    private val prepare: PrepareBulkBatch,
) {
    /** Delegates reservation and duplicate-identity rules to the demo domain policy. */
    suspend operator fun invoke(draft: BulkDraft): Outcome<BulkBatch> =
        when (val result = prepare(draft)) {
            is Outcome.Failure -> result
            is Outcome.Success -> repository.transact { DemoBulkPolicy.queue(it, result.value) }
        }
}

/**
 * Runs local preview progress in the caller's scope, with no workers or external provider calls.
 */
class RunDemoBulkBatch(private val repository: SessionRepository) {
    /** Saves each transition before proceeding; unknown outcomes stay processing until resumed. */
    suspend operator fun invoke(batchId: String): Outcome<Unit> {
        while (true) {
            val next = repository.transact {
                val size = it.batches.find { batch -> batch.id == batchId }?.recipients?.size ?: 0
                DemoBulkPolicy.beginNextGroup(it, batchId, if (size > 20) 25 else 1)
            }
            if (next is Outcome.Failure) return next
            val recipients = (next as Outcome.Success).value
            if (recipients.isEmpty()) return Outcome.Success(Unit)
            delay(650)
            val result = repository.transact {
                DemoBulkPolicy.finishGroup(
                    it,
                    batchId,
                    recipients.associate { row -> row.details.id to row.attempts },
                )
            }
            if (result is Outcome.Failure) return result
        }
    }
}

/** Coordinates failed-only recovery against the shared atomic demo session repository. */
class RetryDemoBulkBatch(private val repository: SessionRepository) {
    /** Reserves known-failed recipients while preserving successful and pending outcomes. */
    suspend operator fun invoke(batchId: String): Outcome<BulkBatch> = repository.transact {
        DemoBulkPolicy.retry(it, batchId)
    }
}
