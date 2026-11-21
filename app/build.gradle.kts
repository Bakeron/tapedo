import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.bakeron.tapedo"
    // Current AndroidX libraries require compiling against Android 17 (API 37);
    // targetSdk stays 36 until the Android 17 behaviour changes are reviewed.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.github.bakeron.tapedo"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "2.3"
        // Instrumented tests (app/src/androidTest) - run on the emulator only, see docs/plan-1a.md.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // "Send the error to the app's author" mails this address. It lives in local.properties (outside Git),
        // so it is not public on GitHub; without it the app has no report button.
        buildConfigField("String", "REPORT_EMAIL", "\"${reportEmail()}\"")
    }

    buildTypes {
        release {
            // Phones only: the x86 / x86_64 SQLCipher libraries (about 4.4 MB) are for emulators,
            // which run the debug build. Debug keeps every ABI.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    androidResources {
        generateLocaleConfig = true
    }
}

/** `tapedo.reportEmail` from local.properties; empty when missing. A plain address only - it goes into Java source. */
fun reportEmail(): String {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return ""
    val value = Properties().apply { file.inputStream().use { load(it) } }
        .getProperty("tapedo.reportEmail").orEmpty().trim()
    require(value.isEmpty() || Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matches(value)) {
        "tapedo.reportEmail in local.properties is not an e-mail address"
    }
    return value
}

// Room writes each database version's schema here (kept in Git) - needed to test migrations later.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)

    implementation(project(":core"))
    // Android network engine for the shared Ktor client (iPhone would use the Darwin engine).
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
