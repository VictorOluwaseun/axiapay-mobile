package com.kesofty.axiapay

import com.kesofty.axiapay.domain.BulkService
import com.kesofty.axiapay.presentation.*
import org.junit.Assert.*
import org.junit.Test

/** Checks picker intents remain presentation edits under the existing payment state owner. */
class ProviderPickerStateTest {
    /** Searching and dismissing never change the current payment's identity, input or funds. */
    @Test
    fun individualSearchRetainsTermsAndResetsWhenTheServiceChanges() {
        val fixture = DemoTestFixture()
        val forms = PaymentFormPresenter(fixture.prepare, fixture.clock)
        val original = forms.create("Electricity", "draft-identity")
        val opened = forms.reduce(original, PaymentIntent.ProviderPicker(ProviderPickerIntent.Open))
        val searched =
            forms.reduce(opened, PaymentIntent.ProviderPicker(ProviderPickerIntent.Search("EKEDC")))
        assertEquals(ProviderPickerState(true, "EKEDC"), searched.providerPicker)
        assertEquals(original.input, searched.input)
        assertEquals(original.requestId, searched.requestId)
        val closed =
            forms.reduce(searched, PaymentIntent.ProviderPicker(ProviderPickerIntent.Close))
        assertEquals(ProviderPickerState(), closed.providerPicker)
        assertEquals(original.input, closed.input)
        val otherService = forms.reduce(searched, PaymentIntent.Service("Airtime"))
        assertEquals(ProviderPickerState(), otherService.providerPicker)
        assertTrue(fixture.session.payments.none { it.id == original.requestId })
    }

    /** Bulk search is bounded and cannot edit recipient terms or survive leaving its editor. */
    @Test
    fun bulkSearchKeepsTheEditorAndClosesWhenNavigatingBack() {
        val fixture = DemoTestFixture()
        var id = 0
        val presenter = BulkFormPresenter(fixture.prepareBulk) { "row-${id++}" }
        val original =
            presenter.reduce(BulkFlowState(), BulkIntent.ChooseService(BulkService.Electricity))
        val opened =
            presenter.reduce(original, BulkIntent.ProviderPicker(ProviderPickerIntent.Open))
        val searched =
            presenter.reduce(
                opened,
                BulkIntent.ProviderPicker(ProviderPickerIntent.Search("x".repeat(100))),
            )
        assertEquals(80, searched.providerPicker.query.length)
        assertEquals(original.editor, searched.editor)
        assertEquals(original.draft, searched.draft)
        assertEquals(
            ProviderPickerState(),
            presenter.reduce(searched, BulkIntent.Back).providerPicker,
        )
    }
}
