import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val alShortiVersionCode = appVersion.getProperty("VERSION_CODE").toInt()
val alShortiVersionName = appVersion.getProperty("VERSION_NAME")

val releaseStoreFile = System.getenv("ALSHORTI_KEYSTORE_FILE")
val releaseStorePassword = System.getenv("ALSHORTI_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ALSHORTI_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ALSHORTI_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.malik.alshurti"
    compileSdk = 36

    defaultConfig {
        // Permanent Android identity. Never change this after distribution: it is
        // what lets Al-Shorti coexist with VibeApp and receive in-place updates.
        applicationId = "com.malik.alshurti"
        minSdk = 29
        targetSdk = 36
        versionCode = alShortiVersionCode
        versionName = alShortiVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/AL2.0",
            "/META-INF/LGPL2.1"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.viewmodel)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Supertonic 3 runs the Arabic neural voice locally after the first model download.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    // Filament-based real GLB character renderer. The old Canvas dog is only a fallback.
    implementation("io.github.sceneview:sceneview:4.33.0")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    debugImplementation(libs.androidx.ui.tooling)
}
