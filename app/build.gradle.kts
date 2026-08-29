import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.abs

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.ksp)
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val encodedSigningStore = rootProject.file(".github/almi_ai_dev_keystore.b64")
val generatedSigningDir = rootProject.layout.buildDirectory.dir("generated/almi-signing").get().asFile
val almiSigningStore = File(generatedSigningDir, "almi-ai-dev.p12")

if (!almiSigningStore.exists() && encodedSigningStore.exists()) {
    generatedSigningDir.mkdirs()
    almiSigningStore.writeBytes(Base64.getMimeDecoder().decode(encodedSigningStore.readText().trim()))
}

private val GLB_MAGIC = 0x46546C67
private val GLB_JSON_CHUNK = 0x4E4F534A
private val GLB_BIN_CHUNK = 0x004E4942

private fun ByteArrayOutputStream.writeLeInt(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}

@Suppress("UNCHECKED_CAST")
private fun addFittedWhiteBaseLayer(
    document: MutableMap<String, Any?>,
    sourceBin: ByteArray,
): ByteArray {
    val nodes = document["nodes"] as? MutableList<MutableMap<String, Any?>> ?: return sourceBin
    val meshes = document["meshes"] as? MutableList<MutableMap<String, Any?>> ?: return sourceBin
    val accessors = document["accessors"] as? MutableList<MutableMap<String, Any?>> ?: return sourceBin
    val bufferViews = document["bufferViews"] as? MutableList<MutableMap<String, Any?>> ?: return sourceBin
    val materials = document["materials"] as? MutableList<MutableMap<String, Any?>> ?: return sourceBin
    val buffers = document["buffers"] as? MutableList<MutableMap<String, Any?>> ?: return sourceBin

    val bodyNodeIndex = nodes.indexOfFirst { it["name"] == "Body" }
    if (bodyNodeIndex < 0) return sourceBin
    val bodyNode = nodes[bodyNodeIndex]
    val bodyMeshIndex = (bodyNode["mesh"] as? Number)?.toInt() ?: return sourceBin
    val bodyMesh = meshes.getOrNull(bodyMeshIndex) ?: return sourceBin
    val primitives = bodyMesh["primitives"] as? MutableList<MutableMap<String, Any?>> ?: return sourceBin
    val sourcePrimitive = primitives.firstOrNull() ?: return sourceBin
    val attributes = sourcePrimitive["attributes"] as? MutableMap<String, Any?> ?: return sourceBin
    val positionAccessorIndex = (attributes["POSITION"] as? Number)?.toInt() ?: return sourceBin
    val indexAccessorIndex = (sourcePrimitive["indices"] as? Number)?.toInt() ?: return sourceBin

    fun accessorInfo(index: Int): Triple<MutableMap<String, Any?>, MutableMap<String, Any?>, Int> {
        val accessor = accessors[index]
        val viewIndex = (accessor["bufferView"] as Number).toInt()
        val view = bufferViews[viewIndex]
        val offset = ((view["byteOffset"] as? Number)?.toInt() ?: 0) +
            ((accessor["byteOffset"] as? Number)?.toInt() ?: 0)
        return Triple(accessor, view, offset)
    }

    val (positionAccessor, positionView, positionOffset) = accessorInfo(positionAccessorIndex)
    if ((positionAccessor["componentType"] as? Number)?.toInt() != 5126 || positionAccessor["type"] != "VEC3") {
        return sourceBin
    }
    val vertexCount = (positionAccessor["count"] as? Number)?.toInt() ?: return sourceBin
    val positionStride = (positionView["byteStride"] as? Number)?.toInt() ?: 12
    val sourceBuffer = ByteBuffer.wrap(sourceBin).order(ByteOrder.LITTLE_ENDIAN)
    val positions = Array(vertexCount) { vertex ->
        val base = positionOffset + vertex * positionStride
        floatArrayOf(
            sourceBuffer.getFloat(base),
            sourceBuffer.getFloat(base + 4),
            sourceBuffer.getFloat(base + 8),
        )
    }

    val (indexAccessor, _, indexOffset) = accessorInfo(indexAccessorIndex)
    val indexCount = (indexAccessor["count"] as? Number)?.toInt() ?: return sourceBin
    val componentType = (indexAccessor["componentType"] as? Number)?.toInt() ?: return sourceBin
    val componentSize = when (componentType) {
        5121 -> 1
        5123 -> 2
        5125 -> 4
        else -> return sourceBin
    }
    val indices = IntArray(indexCount) { index ->
        val position = indexOffset + index * componentSize
        when (componentType) {
            5121 -> sourceBin[position].toInt() and 0xFF
            5123 -> sourceBuffer.getShort(position).toInt() and 0xFFFF
            else -> sourceBuffer.getInt(position)
        }
    }

    val garmentIndices = ArrayList<Int>(indices.size / 4)
    var index = 0
    while (index + 2 < indices.size) {
        val ia = indices[index]
        val ib = indices[index + 1]
        val ic = indices[index + 2]
        if (ia in positions.indices && ib in positions.indices && ic in positions.indices) {
            val a = positions[ia]
            val b = positions[ib]
            val c = positions[ic]
            val y = (a[1] + b[1] + c[1]) / 3f
            val averageAbsoluteX = (abs(a[0]) + abs(b[0]) + abs(c[0])) / 3f
            val top = y in 0.02f..0.52f && averageAbsoluteX < .205f
            val shorts = y in -.45f..0.08f && averageAbsoluteX < .27f
            if (top || shorts) {
                garmentIndices += ia
                garmentIndices += ib
                garmentIndices += ic
            }
        }
        index += 3
    }
    check(garmentIndices.size >= 300) { "Could not derive v12 fitted avatar base layer" }

    val whiteMaterialIndex = materials.size
    materials += linkedMapOf<String, Any?>(
        "name" to "ALMI_BaseWhite",
        "pbrMetallicRoughness" to linkedMapOf<String, Any?>(
            "baseColorFactor" to listOf(.985, .98, .97, 1.0),
            "metallicFactor" to 0.0,
            "roughnessFactor" to .74,
        ),
        "doubleSided" to false,
        "alphaMode" to "OPAQUE",
    )

    val alignedOffset = (sourceBin.size + 3) and -4
    val indexBytes = ByteArray(garmentIndices.size * 4)
    val indexBuffer = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN)
    garmentIndices.forEach(indexBuffer::putInt)
    val newBin = ByteArray(alignedOffset + indexBytes.size)
    sourceBin.copyInto(newBin)
    indexBytes.copyInto(newBin, destinationOffset = alignedOffset)

    val newBufferViewIndex = bufferViews.size
    bufferViews += linkedMapOf<String, Any?>(
        "buffer" to 0,
        "byteOffset" to alignedOffset,
        "byteLength" to indexBytes.size,
        "target" to 34963,
    )
    val newAccessorIndex = accessors.size
    accessors += linkedMapOf<String, Any?>(
        "bufferView" to newBufferViewIndex,
        "byteOffset" to 0,
        "componentType" to 5125,
        "count" to garmentIndices.size,
        "type" to "SCALAR",
        "min" to listOf(garmentIndices.minOrNull() ?: 0),
        "max" to listOf(garmentIndices.maxOrNull() ?: 0),
    )

    val garmentPrimitive = linkedMapOf<String, Any?>(
        "attributes" to LinkedHashMap(attributes),
        "indices" to newAccessorIndex,
        "material" to whiteMaterialIndex,
        "mode" to ((sourcePrimitive["mode"] as? Number)?.toInt() ?: 4),
    )
    sourcePrimitive["targets"]?.let { garmentPrimitive["targets"] = it }

    val garmentMeshIndex = meshes.size
    val garmentMesh = linkedMapOf<String, Any?>(
        "name" to "ALMI_BaseLayerMesh",
        "primitives" to mutableListOf(garmentPrimitive),
    )
    bodyMesh["weights"]?.let { garmentMesh["weights"] = it }
    meshes += garmentMesh

    val garmentNodeIndex = nodes.size
    val garmentNode = linkedMapOf<String, Any?>(
        "name" to "ALMI_BaseLayer",
        "mesh" to garmentMeshIndex,
        "scale" to listOf(1.009, 1.003, 1.009),
    )
    bodyNode["skin"]?.let { garmentNode["skin"] = it }
    bodyNode["weights"]?.let { garmentNode["weights"] = it }
    nodes += garmentNode

    var attached = false
    nodes.take(garmentNodeIndex).forEach { node ->
        val children = node["children"] as? MutableList<Any?> ?: return@forEach
        if (children.any { (it as? Number)?.toInt() == bodyNodeIndex }) {
            children += garmentNodeIndex
            attached = true
        }
    }
    if (!attached) {
        val scenes = document["scenes"] as? MutableList<MutableMap<String, Any?>>
        val sceneIndex = (document["scene"] as? Number)?.toInt() ?: 0
        val sceneNodes = scenes?.getOrNull(sceneIndex)?.get("nodes") as? MutableList<Any?>
        sceneNodes?.add(garmentNodeIndex)
    }

    buffers.firstOrNull()?.set("byteLength", newBin.size)
    return newBin
}

@Suppress("UNCHECKED_CAST")
private fun patchV12AvatarModel(file: File) {
    val source = file.readBytes()
    val input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
    check(input.remaining() >= 20) { "ALMI avatar GLB is truncated" }
    check(input.int == GLB_MAGIC) { "ALMI avatar asset is not a GLB" }
    check(input.int == 2) { "ALMI avatar GLB must be version 2" }
    input.int

    var jsonChunk: ByteArray? = null
    var binChunk: ByteArray? = null
    val otherChunks = mutableListOf<Pair<Int, ByteArray>>()
    while (input.remaining() >= 8) {
        val length = input.int
        val type = input.int
        check(length >= 0 && length <= input.remaining()) { "Invalid ALMI avatar GLB chunk" }
        val payload = ByteArray(length)
        input.get(payload)
        when (type) {
            GLB_JSON_CHUNK -> jsonChunk = payload
            GLB_BIN_CHUNK -> binChunk = payload
            else -> otherChunks += type to payload
        }
    }

    val rawJson = String(checkNotNull(jsonChunk), StandardCharsets.UTF_8)
        .trimEnd(' ', '\u0000', '\n', '\r', '\t')
    val document = JsonSlurper().parseText(rawJson) as MutableMap<String, Any?>
    val nodes = document["nodes"] as? MutableList<MutableMap<String, Any?>> ?: error("ALMI avatar has no nodes")

    nodes.firstOrNull { it["name"] == "LeftUpperArm" }
        ?.set("rotation", listOf(0.0, 0.0, .5, .8660254))
    nodes.firstOrNull { it["name"] == "RightUpperArm" }
        ?.set("rotation", listOf(0.0, 0.0, -.5, .8660254))

    val finalBin = addFittedWhiteBaseLayer(document, checkNotNull(binChunk))
    check(nodes.any { it["name"] == "ALMI_BaseLayer" }) { "v12 avatar base layer was not generated" }

    val encodedJson = JsonOutput.toJson(document).toByteArray(StandardCharsets.UTF_8)
    val paddedJsonSize = (encodedJson.size + 3) and -4
    val paddedJson = ByteArray(paddedJsonSize) { 0x20.toByte() }
    encodedJson.copyInto(paddedJson)

    val chunks = buildList {
        add(GLB_JSON_CHUNK to paddedJson)
        add(GLB_BIN_CHUNK to finalBin)
        addAll(otherChunks)
    }
    val totalLength = 12 + chunks.sumOf { 8 + it.second.size }
    val output = ByteArrayOutputStream(totalLength)
    output.writeLeInt(GLB_MAGIC)
    output.writeLeInt(2)
    output.writeLeInt(totalLength)
    chunks.forEach { (type, payload) ->
        output.writeLeInt(payload.size)
        output.writeLeInt(type)
        output.write(payload)
    }
    val result = output.toByteArray()
    check(result.size == totalLength) { "Could not rebuild v12 avatar GLB" }
    file.writeBytes(result)
}

data class Almi3dAsset(
    val relativePath: String,
    val remoteUrl: String,
    val expectedSize: Long,
    val patchAvatar: Boolean = false,
)

val almi3dGeneratedAssetsDir = layout.buildDirectory.dir("generated/almi-v12-3d-assets").get().asFile

val almi3dAssets = listOf(
    Almi3dAsset(
        relativePath = "almi3d/almi_body_female_v12.glb",
        remoteUrl = "https://raw.githubusercontent.com/kunalkushwaha/vsim/3f97faf85e46d2f9a122b0a8b8d3ccc0af598f91/packages/assets/library/human.glb",
        expectedSize = 2_767_576L,
    ),
    Almi3dAsset(
        relativePath = "almi3d/almi_body_male_v12.glb",
        remoteUrl = "https://raw.githubusercontent.com/kunalkushwaha/vsim/3f97faf85e46d2f9a122b0a8b8d3ccc0af598f91/packages/assets/library/man.glb",
        expectedSize = 2_889_028L,
    ),
    Almi3dAsset(
        relativePath = "almi3d/almi_avatar_lite.glb",
        remoteUrl = "https://raw.githubusercontent.com/gokulsenthilkumar3/Ultimate/f062df0bf969d034e3d8a9f76d688500fe38e587/growthtrack-ultimate/public/assets/models/humanoid-base-lite.glb",
        expectedSize = 5_278_868L,
        patchAvatar = true,
    ),
    Almi3dAsset(
        relativePath = "almi3d/digital/vitruvian_body.glb",
        remoteUrl = "https://raw.githubusercontent.com/ibrews/VitruvianGodot/bdecdcd537b4031fdd0fb299b7e4f93f084fffa0/godot_project/vitruvian_body.glb",
        expectedSize = 6_879_364L,
    ),
    Almi3dAsset(
        relativePath = "almi3d/digital/vitruvian_head.glb",
        remoteUrl = "https://raw.githubusercontent.com/ibrews/VitruvianGodot/bdecdcd537b4031fdd0fb299b7e4f93f084fffa0/godot_project/vitruvian_head.glb",
        expectedSize = 10_189_832L,
    ),
    Almi3dAsset(
        relativePath = "almi3d/digital/vitruvian_hair.glb",
        remoteUrl = "https://raw.githubusercontent.com/ibrews/VitruvianGodot/bdecdcd537b4031fdd0fb299b7e4f93f084fffa0/godot_project/vitruvian_hair_rigged.glb",
        expectedSize = 37_694_332L,
    ),
)

val prepareAlmi3dAssets by tasks.registering {
    outputs.dir(almi3dGeneratedAssetsDir)
    outputs.upToDateWhen { false }
    doLast {
        if (almi3dGeneratedAssetsDir.exists()) almi3dGeneratedAssetsDir.deleteRecursively()
        almi3dGeneratedAssetsDir.mkdirs()

        almi3dAssets.forEach { asset ->
            val target = File(almi3dGeneratedAssetsDir, asset.relativePath)
            target.parentFile.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.download")
            if (temporary.exists()) temporary.delete()

            val connection = URI(asset.remoteUrl).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 300_000
                setRequestProperty("User-Agent", "ALMI-Android-v12-quality-assets")
            }
            connection.getInputStream().use { inputStream ->
                temporary.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
            }
            check(temporary.length() == asset.expectedSize) {
                "Unexpected size for ${asset.relativePath}: ${temporary.length()} (expected ${asset.expectedSize})"
            }
            check(temporary.renameTo(target)) { "Could not install ${asset.relativePath}" }
            if (asset.patchAvatar) {
                patchV12AvatarModel(target)
                check(target.length() >= asset.expectedSize) { "Patched v12 avatar unexpectedly shrank" }
            }
            check(target.length() > 1_000_000L) { "${asset.relativePath} is unexpectedly small" }
        }

        File(almi3dGeneratedAssetsDir, "almi3d/ASSET_NOTICE.txt").apply {
            parentFile.mkdirs()
            writeText(
                "ALMI v12 3D ASSETS\n\n" +
                    "Body Map female: vsim packages/assets/library/human.glb, pinned repository commit 3f97faf85e46d2f9a122b0a8b8d3ccc0af598f91.\n" +
                    "Body Map male: vsim packages/assets/library/man.glb, same pinned commit.\n" +
                    "These realistic rigged bodies are generated with MPFB2/MakeHuman; vsim CREDITS documents the generated humans and MakeHuman system skin assets as CC0.\n" +
                    "v12 preserves their embedded skin/PBR maps, game_engine skeleton, and animation clips.\n\n" +
                    "Avatar lite originates from MakeHuman HM08 source data in gokulsenthilkumar3/Ultimate. It remains only as a temporary editor fallback while the digital-human editor is validated.\n\n" +
                    "Quality digital-human preview: VitruvianGodot body, FACS head, and rigged hair, pinned repository commit bdecdcd537b4031fdd0fb299b7e4f93f084fffa0.\n" +
                    "These assets are bundled specifically for the v12 multi-asset avatar runtime; high-resolution skin, face, eye, mouth, and hair maps are staged as the next material-quality pass.\n"
            )
        }
    }
}

tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("Lint", ignoreCase = true)
}.configureEach { dependsOn(prepareAlmi3dAssets) }

android {
    namespace = "com.almi.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.almi.ai"
        minSdk = 29
        targetSdk = 36
        versionCode = 30_000 + ciRunNumber
        versionName = "0.9.$ciRunNumber"
        vectorDrawables.useSupportLibrary = true
    }

    androidResources {
        localeFilters += listOf("en", "ar")
        noCompress += "glb"
    }

    sourceSets.getByName("main").assets.srcDir(almi3dGeneratedAssetsDir)

    signingConfigs {
        create("almiDev") {
            storeFile = almiSigningStore
            storePassword = "almi-dev-2026"
            keyAlias = "almi_ai_dev"
            keyPassword = "almi-dev-2026"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("almiDev")
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

    packaging.resources.excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "META-INF/INDEX.LIST",
        "META-INF/io.netty.versions.properties",
    )

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)

    implementation("com.google.android.filament:filament-android:1.71.0")
    implementation("com.google.android.filament:gltfio-android:1.71.0")
    implementation("com.google.android.filament:filament-utils-android:1.71.0")

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    implementation(libs.ktor.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.jsoup)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    debugImplementation(libs.androidx.ui.tooling)
}
