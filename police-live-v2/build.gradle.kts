plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

fun buildSetting(name: String, defaultValue: String = ""): String =
    providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: defaultValue

fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val geminiApiKey = buildSetting("GEMINI_API_KEY")
val geminiVoice = buildSetting("ALSHORTI_GEMINI_POLICE_VOICE", "Gacrux")

android {
    namespace = "com.malik.alshurti.livev2"
    compileSdk = 36

    defaultConfig {
        // Intentionally different package so the clean-room build installs beside the old app.
        applicationId = "com.malik.alshurti.livev2"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0-live-alpha1"

        buildConfigField("String", "GEMINI_API_KEY", quoted(geminiApiKey))
        buildConfigField("String", "GEMINI_POLICE_VOICE", quoted(geminiVoice))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    packaging {
        resources.excludes += setOf("/META-INF/AL2.0", "/META-INF/LGPL2.1")
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

    // The only non-Android runtime dependency in V2: persistent WebSocket transport.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
