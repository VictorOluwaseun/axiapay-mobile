/** Defines the four installable environments and their shared Android build configuration. */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kesofty.axiapay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kesofty.axiapay"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One environment per APK; build types control debugging/optimization, not backend selection.
    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        create("demo") {
            dimension = "environment"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
        }
        create("prod") {
            dimension = "environment"
        }
    }

    sourceSets {
        // Demo has separate Hilt bindings. Backend migration is shared only by these three flavors.
        listOf("dev", "staging", "prod").forEach { environment ->
            getByName(environment).kotlin.directories += "src/nonDemo/java"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Reuse one BOM declaration to align the app and instrumentation-test Compose dependencies.
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Opt in for a single compile task; multi-variant/test compilations share report filenames.
if (providers.gradleProperty("composeReports").map(String::toBoolean).getOrElse(false)) {
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("reports/compose")
        metricsDestination = layout.buildDirectory.dir("reports/compose-metrics")
    }
}

listOf("Dev", "Demo", "Staging", "Prod").forEach { flavor ->
    tasks.register("verify$flavor") {
        group = "verification"
        description = "Verifies $flavor formatting, unit tests, lint and all installable outputs."
        dependsOn(
            ":checkKotlinFormat",
            "test${flavor}DebugUnitTest",
            "lint${flavor}Debug",
            "assemble${flavor}Debug",
            "assemble${flavor}Release",
            "assemble${flavor}DebugAndroidTest",
        )
    }
}
