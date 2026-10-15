package com.kesofty.axiapay

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kesofty.axiapay.application.PreparePayment
import com.kesofty.axiapay.domain.ElectricityMeterType
import com.kesofty.axiapay.presentation.*
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises native snapshot JSON compatibility without executing or replaying a payment. */
@RunWith(AndroidJUnit4::class)
class ElectricityMeterRestorationTest {
    /** A fresh store restores Postpaid on both the displayed form and retained draft. */
    @Test
    fun postpaidRestoresWithTheOriginalDraftIdentity() = runBlocking {
        val handle = SavedStateHandle()
        val clock = Clock.systemDefaultZone()
        val presenter = PaymentFormPresenter(PreparePayment(clock), clock)
        var form = presenter.create("Electricity", "postpaid-draft")
        form = presenter.reduce(form, PaymentIntent.MeterType(ElectricityMeterType.Postpaid))
        form = presenter.reduce(form, PaymentIntent.Recipient("01234567890"))
        form = presenter.reduce(form, PaymentIntent.Amount("4500"))
        val original = NavigationState(sheet = Sheet.Payment(form), draft = form)
        SavedNavigationStore(handle).save(original)
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        assertEquals(
            original,
            refreshRestored(requireNotNull(SavedNavigationStore(restoredHandle).load()), presenter),
        )
    }

    /** Existing drafts without meter terms default to Prepaid and keep their entered values. */
    @Test
    fun olderDraftsDefaultToPrepaidWithoutLosingInput() = runBlocking {
        val handle = SavedStateHandle()
        val clock = Clock.systemDefaultZone()
        val presenter = PaymentFormPresenter(PreparePayment(clock), clock)
        var form = presenter.create("Electricity", "legacy-meter-draft")
        form = presenter.reduce(form, PaymentIntent.Recipient("01234567890"))
        form = presenter.reduce(form, PaymentIntent.Amount("4500"))
        val original = NavigationState(sheet = Sheet.Payment(form), draft = form)
        val store = SavedNavigationStore(handle)
        store.save(original)
        val key = handle.keys().single()
        val legacy = JSONObject(handle.get<String>(key)!!)
        legacy.getJSONObject("sheet").remove("meterType")
        legacy.getJSONObject("draft").remove("meterType")
        handle[key] = legacy.toString()
        assertEquals(
            ElectricityMeterType.Prepaid,
            store.load()!!.draft!!.input.electricityMeterType,
        )
        assertEquals(original, refreshRestored(requireNotNull(store.load()), presenter))
    }

    /** Recomputes derived validation as the real ViewModel does after loading retained input. */
    private fun refreshRestored(
        state: NavigationState,
        presenter: PaymentFormPresenter,
    ): NavigationState =
        state.copy(
            sheet = Sheet.Payment(presenter.refresh((state.sheet as Sheet.Payment).form)),
            draft = presenter.refresh(requireNotNull(state.draft)),
        )
}
