package com.kesofty.axiapay.ui.components

import java.util.Locale

/** Supplies display abbreviations only; provider identities and eligibility remain caller-owned. */
internal fun electricityProviderCode(provider: String): String =
    when (provider) {
        "Ikeja Electric" -> "IE"
        "Eko Electric" -> "EKEDC"
        "Abuja Electric" -> "AEDC"
        "Kano Electric" -> "KEDCO"
        "Port Harcourt Electric" -> "PHED"
        "Jos Electric" -> "JED"
        "Kaduna Electric" -> "KAEDCO"
        "Enugu Electric" -> "EEDC"
        "Ibadan Electric" -> "IBEDC"
        "Benin Electric" -> "BEDC"
        "Aba Electric" -> "APLE"
        "Yola Electric" -> "YEDC"
        else -> ""
    }

/** Matches names and common abbreviations without changing a choice or its payment identity. */
internal fun matchesElectricityProvider(provider: String, query: String): Boolean {
    if (query.isBlank()) return true
    val normalizedQuery = normalizeProviderSearch(query)
    if (normalizedQuery.isEmpty()) return false
    val aliases =
        when (provider) {
            "Ikeja Electric" -> "IKEDC IKEDCO"
            "Eko Electric" -> "EKEDP"
            "Kano Electric" -> "KEDC"
            "Port Harcourt Electric" -> "PHEDC"
            "Jos Electric" -> "JEDC"
            "Kaduna Electric" -> "KAEDC KDE"
            "Aba Electric" -> "APL Aba Power"
            else -> ""
        }
    val fullName = provider.replace(" Electric", " Electricity Distribution")
    return listOf(provider, fullName, electricityProviderCode(provider), aliases).any {
        normalizeProviderSearch(it).contains(normalizedQuery)
    }
}

/** Makes local search tolerant of case, spacing and punctuation without locale-dependent casing. */
private fun normalizeProviderSearch(value: String): String =
    value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
