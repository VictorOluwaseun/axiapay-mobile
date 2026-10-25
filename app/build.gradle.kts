/** Defines the dev and demo environments and their shared Android build configuration. */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Export reviewed schemas so later versions can migrate durable dashboard snapshots.
room { schemaDirectory("$projectDir/schemas") }

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
            buildConfigField(
                "String",
                "API_BASE_URL",
                providers
                    .gradleProperty("axiapay.dev.apiBaseUrl")
                    .orElse("http://10.0.2.2:4000/v1/")
                    .get()
                    .let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" },
            )
            buildConfigField("boolean", "API_ALLOW_LOCAL_HTTP", "true")
        }
        create("demo") {
            dimension = "environment"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "API_BASE_URL", "\"\"")
            buildConfigField("boolean", "API_ALLOW_LOCAL_HTTP", "false")
        }
    }

    sourceSets {
        // Identical synthetic import files exercise desktop unit tests and Android parsers.
        getByName("test").resources.directories += "src/sharedTest/resources"
        getByName("androidTest").assets.directories += "src/sharedTest/resources"
        // Dev uses API bindings; demo keeps its separate Hilt bindings.
        getByName("dev").kotlin.directories += "src/nonDemo/java"
    }

    buildTypes {
        release {
            proguardFiles("proguard-rules.pro")
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

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.okhttp.coroutines)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json.test)
    implementation(libs.apache.poi)
    implementation(libs.apache.csv)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
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
    androidTestImplementation(libs.okhttp.mockwebserver)
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

listOf("Dev", "Demo").forEach { flavor ->
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
