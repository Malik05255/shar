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

fun buildSetting(name: String, defaultValue: String = ""): String =
    providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: defaultValue

fun quotedBuildConfig(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val geminiApiKey = buildSetting("GEMINI_API_KEY")
val geminiPoliceVoice = buildSetting("ALSHORTI_GEMINI_POLICE_VOICE", "Gacrux")
val geminiStaffVoice = buildSetting("ALSHORTI_GEMINI_STAFF_VOICE", "Sulafat")

android {
    namespace = "com.malik.alshurti"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.malik.alshurti"
        minSdk = 29
        targetSdk = 36
        versionCode = alShortiVersionCode
        versionName = alShortiVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Debug/prototype builds inject the key directly. Production V2 must move to an ephemeral
        // Live API token before public distribution.
        buildConfigField("String", "GEMINI_API_KEY", quotedBuildConfig(geminiApiKey))
        buildConfigField("String", "GEMINI_POLICE_VOICE", quotedBuildConfig(geminiPoliceVoice))
        buildConfigField("String", "GEMINI_STAFF_VOICE", quotedBuildConfig(geminiStaffVoice))
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
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
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
        buildConfig = true
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.viewmodel)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // V2: one persistent Gemini Live WebSocket instead of ASR -> LLM -> TTS chaining.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    // Legacy dependencies remain only so the old implementation still compiles while V2 is proven.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation("io.github.sceneview:sceneview:4.33.0")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    debugImplementation(libs.androidx.ui.tooling)
}
