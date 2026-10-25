/** Shares formatting and complete flavor verification without changing runtime dependencies. */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

// Resolve the same pinned CLI used by local contributors; no global formatter installation needed.
val kotlinFormatter = configurations.create("kotlinFormatter")

dependencies {
    kotlinFormatter("org.jetbrains.kotlinx:ktfmt:${libs.versions.ktfmt.get()}:with-dependencies")
}

val kotlinSources =
    fileTree(rootDir) {
        include("app/src/**/*.kt", "*.gradle.kts", "app/*.gradle.kts")
    }

tasks.register<JavaExec>("checkKotlinFormat") {
    group = "verification"
    description = "Checks all authored Kotlin sources and Gradle scripts."
    classpath = kotlinFormatter
    mainClass = "org.jetbrains.kotlinx.ktfmt.cli.Main"
    inputs.files(kotlinSources)
    args("--kotlinlang-style", "--dry-run", "--set-exit-if-changed")
    args(kotlinSources.files.sorted().map { it.absolutePath })
}

tasks.register<JavaExec>("formatKotlin") {
    group = "formatting"
    description = "Formats all authored Kotlin sources and Gradle scripts."
    classpath = kotlinFormatter
    mainClass = "org.jetbrains.kotlinx.ktfmt.cli.Main"
    args("--kotlinlang-style")
    args(kotlinSources.files.sorted().map { it.absolutePath })
}

tasks.register("verifyAllFlavors") {
    group = "verification"
    description = "Checks formatting, unit tests, lint and debug/release/test APKs for all flavors."
    dependsOn(listOf("Dev", "Demo").map { ":app:verify$it" })
}
