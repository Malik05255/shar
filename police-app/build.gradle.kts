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

val elevenLabsApiKey = buildSetting("ELEVENLABS_API_KEY")
val elevenLabsVoiceId = buildSetting(
    "ALSHORTI_ELEVENLABS_VOICE_ID",
    // Jeddawi: native Saudi male / Jeddah accent. This is intentionally not a
    // generic multilingual English voice.
    "yXEnnEln9armDCyhkXcA"
)
val elevenLabsStaffVoiceId = buildSetting(
    "ALSHORTI_ELEVENLABS_STAFF_VOICE_ID",
    elevenLabsVoiceId
)

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

        // Never commit the API key. Local builds can use -PELEVENLABS_API_KEY=...
        // or the ELEVENLABS_API_KEY environment variable.
        buildConfigField("String", "ELEVENLABS_API_KEY", quotedBuildConfig(elevenLabsApiKey))
        buildConfigField("String", "ELEVENLABS_VOICE_ID", quotedBuildConfig(elevenLabsVoiceId))
        buildConfigField("String", "ELEVENLABS_STAFF_VOICE_ID", quotedBuildConfig(elevenLabsStaffVoiceId))
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
            // CI/private test APKs use the permanent app certificate whenever the signing secrets
            // are available. That lets a test APK receive later signed updates in-place instead of
            // being stranded on a one-off Android debug certificate.
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

    // Legacy local voice code remains buildable for development/reference only.
    // It is no longer on the production speech path because the product contract
    // requires a native Saudi human-sounding voice and forbids robotic fallback.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    // Filament-based real GLB character renderer. The old Canvas dog is only a fallback.
    implementation("io.github.sceneview:sceneview:4.33.0")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    debugImplementation(libs.androidx.ui.tooling)
}
