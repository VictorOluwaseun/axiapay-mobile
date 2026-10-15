package com.kesofty.axiapay

import com.kesofty.axiapay.ui.components.matchesElectricityProvider
import org.junit.Assert.*
import org.junit.Test

/** Checks tolerant local discovery without changing provider identity or eligibility. */
class ElectricityProviderSearchTest {
    /** Common bill abbreviations and spaced names resolve to their existing provider choice. */
    @Test
    fun namesAndAbbreviationsTolerateCaseAndSeparators() {
        assertTrue(matchesElectricityProvider("Eko Electric", " eKe-Dc "))
        assertTrue(matchesElectricityProvider("Eko Electric", "EKEDP"))
        assertTrue(matchesElectricityProvider("Ikeja Electric", "IKEDC"))
        assertTrue(matchesElectricityProvider("Port Harcourt Electric", "port-harcourt"))
        assertTrue(matchesElectricityProvider("Port Harcourt Electric", "PHED"))
        assertTrue(matchesElectricityProvider("Kaduna Electric", "KAEDC"))
        assertTrue(matchesElectricityProvider("Aba Electric", "Aba power"))
        assertTrue(matchesElectricityProvider("Yola Electric", "YEDC"))
        assertFalse(matchesElectricityProvider("Ikeja Electric", "EKEDC"))
    }

    /** Clearing shows all choices; unmatched or punctuation-only input cannot imply a selection. */
    @Test
    fun blankAndUnmatchedQueriesHaveExplicitResults() {
        assertTrue(matchesElectricityProvider("Benin Electric", "  "))
        assertTrue(matchesElectricityProvider("Enugu Electric", "electricity distribution"))
        assertFalse(matchesElectricityProvider("Abuja Electric", "Unlisted supplier"))
        assertFalse(matchesElectricityProvider("Yola Electric", "..."))
    }
}
