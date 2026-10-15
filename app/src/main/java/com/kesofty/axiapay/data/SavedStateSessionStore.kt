package com.kesofty.axiapay.data

import androidx.lifecycle.SavedStateHandle
import com.kesofty.axiapay.domain.*
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Activity/process restoration only, preserving the original demo-session lifetime. No disk ledger.
 */
class SavedStateSessionStore(private val handle: SavedStateHandle) : DemoSessionStore {
    override fun load(): DemoSession? = handle.get<String>(KEY)?.let(SessionSnapshotCodec::decode)

    override fun save(session: DemoSession) {
        handle[KEY] = SessionSnapshotCodec.encode(session)
    }

    private companion object {
        const val KEY = "demo.session.v1"
    }
}

/** JSON is a private persistence format, never exposed to use cases or UI contracts. */
internal object SessionSnapshotCodec {
    /** Encodes the complete demo snapshot, including its session-scoped connection preference. */
    fun encode(session: DemoSession): String =
        JSONObject()
            .apply {
                put(
                    "batches",
                    JSONArray().apply {
                        session.batches.forEach { put(BulkSnapshotCodec.encode(it)) }
                    },
                )
                put("balance", session.balance)
                put("offline", session.offline)
                put("connectionMode", session.connectionMode.name)
                put("cachedAt", session.cachedAt?.toString())
                put("sms", JSONArray().apply { session.smsOutbox.forEach { put(smsJson(it)) } })
                put("currency", session.currency.name)
                put("hidden", session.hidden)
                put("read", session.notificationsRead)
                put("reminders", session.reminderEnabled)
                put(
                    "payments",
                    JSONArray().apply { session.payments.forEach { put(paymentJson(it)) } },
                )
                put("plans", JSONArray().apply { session.plans.forEach { put(planJson(it)) } })
            }
            .toString()

    /** Restores values only; snapshots without a connection preference default to Automatic. */
    fun decode(value: String): DemoSession {
        val json = JSONObject(value)
        val payments = json.getJSONArray("payments")
        val plans = json.getJSONArray("plans")
        return DemoSession(
            json.getLong("balance"),
            List(payments.length()) { payment(payments.getJSONObject(it)) },
            List(plans.length()) { plan(plans.getJSONObject(it)) },
            json.getBoolean("hidden"),
            json.getBoolean("read"),
            json.getBoolean("reminders"),
            Currency.valueOf(json.getString("currency")),
            batches =
                json.optJSONArray("batches")?.let { rows ->
                    List(rows.length()) { BulkSnapshotCodec.decode(rows.getJSONObject(it)) }
                } ?: emptyList(),
            offline = json.optBoolean("offline"),
            connectionMode =
                ConnectionMode.valueOf(
                    json.optString("connectionMode", ConnectionMode.Automatic.name),
                ),
            cachedAt =
                json
                    .optString("cachedAt")
                    .takeIf { it.isNotBlank() }
                    ?.let(java.time.Instant::parse),
            smsOutbox =
                json.optJSONArray("sms")?.let { items ->
                    List(items.length()) { sms(items.getJSONObject(it)) }
                } ?: emptyList(),
        )
    }

    private fun smsJson(s: SmsRequest) =
        JSONObject().apply {
            put(
                "payment",
                JSONObject().apply {
                    put("id", s.id)
                    put("title", s.payment.title)
                    put("detail", s.payment.detail)
                    put("amount", s.payment.amount)
                    put("kind", s.payment.kind.name)
                },
            )
            put("sim", s.sim)
            put("created", s.createdAt.toString())
            put("expires", s.expiresAt.toString())
            put("transport", s.transport.name)
            put("attempts", s.attempts)
        }

    private fun sms(s: JSONObject): SmsRequest {
        val p = s.getJSONObject("payment")
        return SmsRequest(
            PaymentRequest(
                p.getString("id"),
                p.getString("title"),
                p.getString("detail"),
                p.getLong("amount"),
                PaymentKind.valueOf(p.getString("kind")),
            ),
            s.getInt("sim"),
            java.time.Instant.parse(s.getString("created")),
            java.time.Instant.parse(s.getString("expires")),
            SmsTransport.valueOf(s.getString("transport")),
            s.getInt("attempts"),
        )
    }

    private fun paymentJson(p: Payment) =
        JSONObject().apply {
            put("id", p.id)
            put("title", p.title)
            put("detail", p.detail)
            put("amount", p.amount)
            put("kind", p.kind.name)
            put("status", p.status.name)
            put("time", p.time)
            put("recurrence", p.recurrence.name)
            put("currency", p.currency.name)
        }

    private fun planJson(p: BillPlan) =
        JSONObject().apply {
            put("id", p.id)
            put("title", p.title)
            put("detail", p.detail)
            put("amount", p.amount)
            put("kind", p.kind.name)
            put("date", p.date.toString())
            put("enabled", p.enabled)
            put("recurrence", p.recurrence.name)
        }

    private fun payment(p: JSONObject) =
        Payment(
            p.getString("id"),
            p.getString("title"),
            p.getString("detail"),
            p.getLong("amount"),
            PaymentKind.valueOf(p.getString("kind")),
            PaymentStatus.valueOf(p.getString("status")),
            p.getString("time"),
            Recurrence.valueOf(p.getString("recurrence")),
            Currency.valueOf(p.getString("currency")),
        )

    private fun plan(p: JSONObject) =
        BillPlan(
            p.getString("id"),
            p.getString("title"),
            p.getString("detail"),
            p.getLong("amount"),
            PaymentKind.valueOf(p.getString("kind")),
            LocalDate.parse(p.getString("date")),
            p.getBoolean("enabled"),
            Recurrence.valueOf(p.getString("recurrence")),
        )
}
