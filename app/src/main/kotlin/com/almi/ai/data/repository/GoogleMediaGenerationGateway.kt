package com.almi.ai.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.almi.ai.data.network.NetworkClient
import com.almi.ai.data.preferences.GoogleAiStudioSettings
import com.almi.ai.data.preferences.GoogleAiStudioStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GoogleMediaGenerationGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkClient: NetworkClient,
    private val store: GoogleAiStudioStore,
    private val catalogRepository: GoogleAiStudioRepository,
) {
    suspend fun generateImage(
        personImage: String,
        garmentImage: String,
        garmentDescription: String,
    ): Result<GeneratedTryOnImage> = withContext(Dispatchers.IO) {
        runCatching {
            val key = requireKey()
            val settings = requireActiveSettings()
            val selected = settings.imageModelId.ifBlank {
                throw IllegalStateException("google_image_model_missing")
            }
            val catalog = catalogRepository.connect(key).getOrElse {
                throw IllegalStateException("google_catalog_failed", it)
            }
            val pool = if (settings.imagePaid) catalog.paidModels else catalog.freeModels
            val candidates = buildList {
                add(selected)
                addAll(pool.filter { it.outputKind == GoogleOutputKind.IMAGE }.map { it.id })
            }.distinct()

            val person = readImage(personImage)
            val garment = readImage(garmentImage)
            var lastError: Throwable? = null
            for (model in candidates) {
                try {
                    return@runCatching requestTryOnImage(
                        apiKey = key,
                        model = model,
                        person = person,
                        garment = garment,
                        garmentDescription = garmentDescription,
                    )
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            throw IllegalStateException("google_image_fallback_exhausted", lastError)
        }
    }

    suspend fun generateVideo(
        generatedImage: String,
        motion: MotionDirection,
        onStatus: (VideoGenerationStatus) -> Unit,
    ): Result<GeneratedTryOnVideo> = withContext(Dispatchers.IO) {
        runCatching {
            val key = requireKey()
            val settings = requireActiveSettings()
            val selected = settings.videoModelId.ifBlank {
                throw IllegalStateException("google_video_model_missing")
            }
            val catalog = catalogRepository.connect(key).getOrElse {
                throw IllegalStateException("google_catalog_failed", it)
            }
            val pool = if (settings.videoPaid) catalog.paidModels else catalog.freeModels
            val candidates = buildList {
                add(selected)
                addAll(pool.filter { it.outputKind == GoogleOutputKind.VIDEO }.map { it.id })
            }.distinct()

            val image = readImage(generatedImage)
            var lastError: Throwable? = null
            for (model in candidates) {
                try {
                    return@runCatching requestVideo(
                        apiKey = key,
                        model = model,
                        image = image,
                        motion = motion,
                        onStatus = onStatus,
                    )
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            throw IllegalStateException("google_video_fallback_exhausted", lastError)
        }
    }

    private suspend fun requestTryOnImage(
        apiKey: String,
        model: String,
        person: InlineImage,
        garment: InlineImage,
        garmentDescription: String,
    ): GeneratedTryOnImage {
        val request = JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", buildTryOnPrompt(garmentDescription)))
                    .put(person.toInteractionBlock())
                    .put(garment.toInteractionBlock())
            )
            .put(
                "response_format",
                JSONObject()
                    .put("type", "image")
                    .put("mime_type", "image/png")
                    .put("aspect_ratio", "9:16")
                    .put("image_size", "1K")
            )

        val response = networkClient().post(INTERACTIONS_URL) {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(request.toString())
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("google_image_http_${response.status.value}:${googleError(body)}")
        }

        val root = JSONObject(body)
        val imageBlock = findGeneratedImage(root)
            ?: throw IllegalStateException("google_image_empty_response")
        val data = imageBlock.optString("data")
        if (data.isBlank()) throw IllegalStateException("google_image_empty_data")
        val bytes = Base64.decode(data, Base64.DEFAULT)
        if (bytes.isEmpty()) throw IllegalStateException("google_image_empty_bytes")
        val mime = imageBlock.optString("mime_type").ifBlank { "image/png" }

        return GeneratedTryOnImage(
            uri = saveImage(bytes, mime),
            model = model,
            costUsd = null,
        )
    }

    private suspend fun requestVideo(
        apiKey: String,
        model: String,
        image: InlineImage,
        motion: MotionDirection,
        onStatus: (VideoGenerationStatus) -> Unit,
    ): GeneratedTryOnVideo {
        val endpoint = "$BASE_URL/models/$model:predictLongRunning"
        val instance = JSONObject()
            .put("prompt", buildVideoPrompt(motion))
            .put(
                "image",
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", image.mimeType)
                        .put("data", image.base64)
                )
            )
        val request = JSONObject()
            .put("instances", JSONArray().put(instance))
            .put(
                "parameters",
                JSONObject()
                    .put("aspectRatio", "9:16")
                    .put("resolution", "720p")
                    .put("numberOfVideos", 1)
            )

        onStatus(VideoGenerationStatus.SUBMITTING)
        val submit = networkClient().post(endpoint) {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(request.toString())
        }
        val submitBody = submit.bodyAsText()
        if (!submit.status.isSuccess()) {
            throw IllegalStateException("google_video_http_${submit.status.value}:${googleError(submitBody)}")
        }
        val operationName = JSONObject(submitBody).optString("name")
        if (operationName.isBlank()) throw IllegalStateException("google_video_operation_missing")

        var completed: JSONObject? = null
        repeat(MAX_VIDEO_POLLS) { attempt ->
            if (completed != null) return@repeat
            if (attempt > 0) delay(VIDEO_POLL_INTERVAL_MS)
            onStatus(VideoGenerationStatus.PROCESSING)
            val poll = networkClient().get("$BASE_URL/$operationName") {
                header("x-goog-api-key", apiKey)
            }
            val pollBody = poll.bodyAsText()
            if (!poll.status.isSuccess()) {
                throw IllegalStateException("google_video_poll_${poll.status.value}:${googleError(pollBody)}")
            }
            val json = JSONObject(pollBody)
            json.optJSONObject("error")?.let {
                throw IllegalStateException("google_video_failed:${it.optString("message")}")
            }
            if (json.optBoolean("done", false)) completed = json
        }

        val done = completed ?: throw IllegalStateException("google_video_timeout")
        val uri = done.optJSONObject("response")
            ?.optJSONObject("generateVideoResponse")
            ?.optJSONArray("generatedSamples")
            ?.optJSONObject(0)
            ?.optJSONObject("video")
            ?.optString("uri")
            .orEmpty()
        if (uri.isBlank()) throw IllegalStateException("google_video_uri_missing")

        onStatus(VideoGenerationStatus.DOWNLOADING)
        val videoResponse = networkClient().get(uri) {
            header("x-goog-api-key", apiKey)
        }
        if (!videoResponse.status.isSuccess()) {
            throw IllegalStateException("google_video_download_${videoResponse.status.value}")
        }
        val bytes: ByteArray = videoResponse.body()
        if (bytes.isEmpty()) throw IllegalStateException("google_video_empty_bytes")

        return GeneratedTryOnVideo(
            uri = saveVideo(bytes),
            model = model,
            costUsd = null,
        )
    }

    private fun findGeneratedImage(root: JSONObject): JSONObject? {
        val steps = root.optJSONArray("steps") ?: return null
        for (stepIndex in 0 until steps.length()) {
            val step = steps.optJSONObject(stepIndex) ?: continue
            if (!step.optString("type").equals("model_output", true)) continue
            val content = step.optJSONArray("content") ?: continue
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type").equals("image", true) && block.optString("data").isNotBlank()) {
                    return block
                }
            }
        }
        return null
    }

    private fun requireKey(): String = store.apiKey().takeIf(String::isNotBlank)
        ?: throw IllegalStateException("google_api_key_missing")

    private fun requireActiveSettings(): GoogleAiStudioSettings {
        val settings = store.settings.value
        if (!settings.connected || !settings.active) throw IllegalStateException("google_not_active")
        return settings
    }

    private suspend fun readImage(source: String): InlineImage {
        if (source.startsWith("data:image/")) {
            val header = source.substringBefore(',')
            val mime = header.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
            return InlineImage(mime, source.substringAfter(','))
        }
        val (bytes, mime) = when {
            source.startsWith("https://") || source.startsWith("http://") -> {
                val response = networkClient().get(source)
                if (!response.status.isSuccess()) throw IllegalStateException("google_reference_download_failed")
                val bytes: ByteArray = response.body()
                val mime = response.headers[HttpHeaders.ContentType]
                    ?.substringBefore(';')
                    ?.takeIf { it.startsWith("image/") }
                    ?: guessMime(source)
                bytes to mime
            }
            else -> {
                val uri = Uri.parse(source)
                val resolver = context.contentResolver
                val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: guessMime(source)
                val bytes = when (uri.scheme) {
                    "content", "android.resource" -> resolver.openInputStream(uri)?.use { it.readBytes() }
                    "file" -> File(requireNotNull(uri.path)).readBytes()
                    else -> File(source).takeIf(File::exists)?.readBytes()
                } ?: throw IllegalStateException("google_reference_read_failed")
                bytes to mime
            }
        }
        if (bytes.isEmpty()) throw IllegalStateException("google_reference_empty")
        if (bytes.size > MAX_REFERENCE_BYTES) throw IllegalStateException("google_reference_too_large")
        return InlineImage(mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun InlineImage.toInteractionBlock(): JSONObject = JSONObject()
        .put("type", "image")
        .put("mime_type", mimeType)
        .put("data", base64)

    private fun buildTryOnPrompt(garmentDescription: String): String = """
        Create a photorealistic virtual try-on using the two reference images.
        Reference 1 is the person. Reference 2 is the garment/product.
        Put the exact garment from reference 2 on the exact person from reference 1.
        Preserve the person's identity, face, hair, skin tone, body proportions, hands, pose and background.
        Preserve the garment's exact color, print, logos, cut, material and proportions.
        Do not slim, reshape, beautify or change the person's identity.
        Make fabric folds, fit, lighting, shadows and perspective physically realistic.
        Garment context: ${garmentDescription.ifBlank { "Use the garment exactly as shown." }}
        Return only the final try-on image.
    """.trimIndent()

    private fun buildVideoPrompt(motion: MotionDirection): String = when (motion) {
        MotionDirection.TURN -> "Animate this exact try-on image into a short realistic fashion turn. Preserve face, identity, garment details and proportions. No outfit changes."
        MotionDirection.WALK -> "Animate this exact try-on image into a short natural fashion walk. Preserve face, identity, garment details and proportions. No outfit changes."
        MotionDirection.DETAIL -> "Create a subtle fashion detail video from this exact try-on image with gentle camera movement. Preserve identity and every garment detail."
    }

    private fun googleError(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")
    }.getOrNull().orEmpty().take(220)

    private fun guessMime(source: String): String = when {
        source.contains(".webp", true) -> "image/webp"
        source.contains(".jpg", true) || source.contains(".jpeg", true) -> "image/jpeg"
        else -> "image/png"
    }

    private fun saveImage(bytes: ByteArray, mime: String): String {
        val ext = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val directory = File(context.filesDir, "tryon_results").apply { mkdirs() }
        val file = File(directory, "almi_google_${System.currentTimeMillis()}.$ext")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    private fun saveVideo(bytes: ByteArray): String {
        val directory = File(context.filesDir, "tryon_results").apply { mkdirs() }
        val file = File(directory, "almi_google_${System.currentTimeMillis()}.mp4")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    private data class InlineImage(val mimeType: String, val base64: String)

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val INTERACTIONS_URL = "$BASE_URL/interactions"
        private const val MAX_REFERENCE_BYTES = 18 * 1024 * 1024
        private const val MAX_VIDEO_POLLS = 90
        private const val VIDEO_POLL_INTERVAL_MS = 10_000L
    }
}
