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

        // Test builds can inject Gemini directly. Production should proxy this secret server-side
        // so the API key is never distributed inside a public APK. The production conversation
        // path also has local Whisper + Supertonic fallbacks that require no API key after install.
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
        // sherpa-onnx v1.13.4 is built against ONNX Runtime 1.27.0 and bundles the same
        // native runtime. Keep exactly one ABI-identical libonnxruntime.so in the APK while
        // retaining the Java ONNX Runtime API used by SupertonicCore.
        jniLibs.pickFirsts += setOf("**/libonnxruntime.so")
    }
}

// Migration-only allowlist. These are the old full-frame MP4 fallbacks already shipped before the
// runtime-3D architecture. New scenarios must be composed from independent GLB actors + animation
// clips instead of adding another cinematic video.
val legacyFullSceneVideos = setOf(
    "dog_idle_loop.mp4",
    "dog_talk_seated.mp4",
    "dog_stand_up.mp4",
    "dog_talk_standing.mp4",
    "dog_approach_camera.mp4",
    "dog_answer_phone.mp4",
    "dog_walk_to_door.mp4",
    "dog_review_file.mp4",
    "dog_sit_down.mp4",
    "dog_return_to_desk.mp4",
    "dog_return_from_camera.mp4"
)

val guardCinematicVideoBloat by tasks.registering {
    group = "verification"
    description = "Rejects new full-scene MP4 assets; runtime scenes must use independent 3D actors."
    doLast {
        val rawDir = file("src/main/res/raw")
        if (!rawDir.exists()) return@doLast

        val bundledVideos = rawDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            .map { it.name }
            .toSet()

        val unexpected = bundledVideos - legacyFullSceneVideos
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "New full-scene cinematic MP4 assets are forbidden: ${unexpected.sorted().joinToString()}. " +
                    "Add the object as an independent GLB/PBR actor and reuse skeletal animation clips instead."
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(guardCinematicVideoBloat)
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

    // Keep this aligned with sherpa-onnx v1.13.4's Android build default.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")
    implementation("io.github.sceneview:sceneview:4.33.0")
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    debugImplementation(libs.androidx.ui.tooling)
}
