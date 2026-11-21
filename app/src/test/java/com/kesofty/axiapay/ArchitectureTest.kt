package com.kesofty.axiapay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Checks dependency direction in the app and the shared wallet contracts. */
class ArchitectureTest {
    private val forbiddenLayers =
        mapOf(
            "domain" to setOf("application", "data", "presentation", "ui", "di"),
            "application" to setOf("data", "presentation", "ui", "di"),
            "data" to setOf("application", "presentation", "ui", "di"),
            "presentation" to setOf("data", "ui", "di"),
            "ui" to setOf("application", "data", "di"),
        )
    private val platformImports =
        listOf("android.", "androidx.", "org.json.", "dagger.", "javax.inject.", "jakarta.inject.")

    /** Include each production source root and fail if a module or source set moves. */
    private fun productionSources(): List<File> =
        listOf(
                "src/main/java",
                "src/demo/java",
                "src/nonDemo/java",
                "../wallet/domain/src/main/kotlin",
            )
            .flatMap { directory ->
                File(directory)
                    .walkTopDown()
                    .filter { it.extension == "kt" }
                    .toList()
                    .also {
                        assertTrue("No Kotlin sources in $directory", it.isNotEmpty())
                    }
            }

    /** Resolve both layer-first and feature-first packages, including wildcard imports. */
    private fun layer(qualifiedName: String): String? {
        val prefix = "com.kesofty.axiapay."
        if (!qualifiedName.startsWith(prefix)) return null
        return qualifiedName.removePrefix(prefix).split('.').firstOrNull {
            it in forbiddenLayers || it == "di"
        }
    }

    /** Check every production source against the dependency rules of its declared package. */
    @Test
    fun layersDependInwardAcrossPackagesAndModules() {
        productionSources().forEach { file ->
            val lines = file.readLines()
            val sourceLayer =
                layer(lines.first { it.startsWith("package ") }.removePrefix("package "))
            val forbidden = forbiddenLayers[sourceLayer] ?: return@forEach
            val inner = sourceLayer == "domain" || sourceLayer == "application"
            for (statement in lines.filter { it.startsWith("import ") }) {
                val imported = statement.removePrefix("import ").substringBefore(" as ")
                assertFalse("$file imports forbidden $imported", layer(imported) in forbidden)
                assertFalse(
                    "$file imports framework $imported",
                    inner && platformImports.any(imported::startsWith),
                )
            }
        }
    }

    /** Compose views render state and emit intents in the app and in each feature. */
    @Test
    fun viewsDoNotOwnMutableApplicationStateOrAccessRepositories() {
        val forbidden =
            listOf(
                "mutableStateOf(",
                "mutableStateListOf(",
                "rememberSaveable",
                "SessionRepository",
                "WalletRepository",
                "FundingRepository",
                "SubmitDemoPayment",
            )
        productionSources().forEach { file ->
            val source = file.readText()
            val declaredPackage =
                source.lineSequence().first { it.startsWith("package ") }.removePrefix("package ")
            if (layer(declaredPackage) == "ui") {
                forbidden.forEach {
                    assertFalse("$file bypasses intent flow with $it", source.contains(it))
                }
            }
        }
    }
}
