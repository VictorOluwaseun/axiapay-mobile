package com.kesofty.axiapay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Cheap dependency-direction guard while this small app remains a single Gradle module. */
class ArchitectureTest {
    private val root = File("src/main/java/com/kesofty/axiapay")

    @Test
    fun `inner layers never import Android presentation or concrete data adapters`() {
        assertTrue(root.isDirectory)
        val forbidden =
            mapOf(
                "domain" to
                    listOf(
                        "android.",
                        "androidx.",
                        "org.json.",
                        "com.kesofty.axiapay.application.",
                        "com.kesofty.axiapay.data.",
                        "com.kesofty.axiapay.presentation.",
                        "com.kesofty.axiapay.ui.",
                        "com.kesofty.axiapay.di.",
                    ),
                "application" to
                    listOf(
                        "android.",
                        "androidx.",
                        "org.json.",
                        "com.kesofty.axiapay.data.",
                        "com.kesofty.axiapay.presentation.",
                        "com.kesofty.axiapay.ui.",
                        "com.kesofty.axiapay.di.",
                    ),
                "data" to
                    listOf(
                        "com.kesofty.axiapay.presentation.",
                        "com.kesofty.axiapay.ui.",
                        "com.kesofty.axiapay.application.",
                        "com.kesofty.axiapay.di.",
                    ),
                "presentation" to
                    listOf(
                        "com.kesofty.axiapay.data.",
                        "com.kesofty.axiapay.ui.",
                        "com.kesofty.axiapay.di.",
                    ),
                "ui" to
                    listOf(
                        "com.kesofty.axiapay.data.",
                        "com.kesofty.axiapay.application.",
                        "com.kesofty.axiapay.di.",
                    ),
            )
        forbidden.forEach { (layer, prefixes) ->
            File(root, layer)
                .walkTopDown()
                .filter { it.extension == "kt" }
                .forEach { file ->
                    val imports =
                        file
                            .readLines()
                            .filter { it.startsWith("import ") }
                            .map { it.removePrefix("import ") }
                    prefixes.forEach { prefix ->
                        assertFalse(
                            "${file.name} imports forbidden $prefix",
                            imports.any { it.startsWith(prefix) },
                        )
                    }
                }
        }
    }

    @Test
    fun `views do not own mutable application state or access repositories`() {
        File(root, "ui")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                val source = file.readText()
                listOf(
                        "mutableStateOf(",
                        "mutableStateListOf(",
                        "rememberSaveable",
                        "SessionRepository",
                        "SubmitDemoPayment",
                    )
                    .forEach { forbidden ->
                        assertFalse(
                            "${file.name} bypasses intent flow with $forbidden",
                            source.contains(forbidden),
                        )
                    }
            }
    }
}
