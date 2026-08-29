package com.almi.ai.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.almi.ai.data.network.NetworkClient
import com.almi.ai.data.preferences.AiMode
import com.almi.ai.data.preferences.AlmiPreferences
import com.almi.ai.data.preferences.ApiKeyRecord
import com.almi.ai.data.preferences.ApiKeyVault
import com.almi.ai.data.preferences.CustomAiConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
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
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MediaGenerationGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkClient: NetworkClient,
    private val preferences: AlmiPreferences,
    private val apiKeyVault: ApiKeyVault,
    private val openRouterCatalogRepository: OpenRouterCatalogRepository,
) {
    suspend fun generateImage(
        personImage: String,
        garmentImage: String,
        garmentDescription: String,
    ): Result<GeneratedTryOnImage> = withContext(Dispatchers.IO) {
        runCatching {
            val references = JSONArray()
                .put(imageReference(personImage))
                .put(imageReference(garmentImage))

            when (preferences.currentAiMode()) {
                AiMode.OPENROUTER -> requestOpenRouterImage(references, garmentDescription)
                AiMode.CUSTOM -> {
                    val config = requireCustomImageConfig()
                    requestImage(
                        endpoint = resolveEndpoint(config.baseUrl, config.imageEndpoint),
                        apiKey = config.apiKey,
                        model = config.imageModel,
                        references = references,
                        garmentDescription = garmentDescription,
                        openRouter = isOpenRouter(config.baseUrl),
                    )
                }
                // Free/no-key mode must never silently fall back to a stored OpenRouter key.
                // The current verified anonymous route is text-only inside ALMI. Anonymous
                // community image jobs are intentionally not used for private body photos.
                AiMode.FREE_AUTO -> throw IllegalStateException("free_private_image_provider_unavailable")
            }
        }
    }

    suspend fun generateVideo(
        generatedImage: String,
        motion: MotionDirection,
        onStatus: (VideoGenerationStatus) -> Unit,
    ): Result<GeneratedTryOnVideo> = withContext(Dispatchers.IO) {
        runCatching {
            val references = JSONArray().put(imageReference(generatedImage))

            when (preferences.currentAiMode()) {
                AiMode.OPENROUTER -> requestOpenRouterVideo(references, motion, onStatus)
                AiMode.CUSTOM -> {
                    val config = requireCustomVideoConfig()
                    requestVideo(
                        endpoint = resolveEndpoint(config.baseUrl, config.videoEndpoint),
                        baseUrl = config.baseUrl,
                        apiKey = config.apiKey,
                        model = config.videoModel,
                        references = references,
                        motion = motion,
                        openRouter = isOpenRouter(config.baseUrl),
                        onStatus = onStatus,
                    )
                }
                AiMode.FREE_AUTO -> throw IllegalStateException("free_video_provider_unavailable")
            }
        }
    }

    private suspend fun requestOpenRouterImage(
        references: JSONArray,
        garmentDescription: String,
    ): GeneratedTryOnImage {
        val keys = requireOpenRouterApiKeys()
        val config = preferences.currentOpenRouterConfig()
        val catalog = openRouterCatalogRepository.loadCatalog(keys.first().secret).getOrElse {
            throw IllegalStateException("openrouter_catalog_failed", it)
        }.filtered(config.freeOnly)
        val models = buildList {
            config.imageModel.takeIf(String::isNotBlank)?.let(::add)
            addAll(catalog.imageModels.map { it.id })
        }.distinct()
        if (models.isEmpty()) throw IllegalStateException("openrouter_image_unavailable")
        return requestImageByModelIds(
            modelIds = models,
            apiKeys = keys,
            references = references,
            garmentDescription = garmentDescription,
        )
    }

    private suspend fun requestOpenRouterVideo(
        references: JSONArray,
        motion: MotionDirection,
        onStatus: (VideoGenerationStatus) -> Unit,
    ): GeneratedTryOnVideo {
        val keys = requireOpenRouterApiKeys()
        val config = preferences.currentOpenRouterConfig()
        val catalog = openRouterCatalogRepository.loadCatalog(keys.first().secret).getOrElse {
            throw IllegalStateException("openrouter_catalog_failed", it)
        }.filtered(config.freeOnly)
        val models = buildList {
            config.videoModel.takeIf(String::isNotBlank)?.let(::add)
            addAll(catalog.videoModels.map { it.id })
        }.distinct()
        if (models.isEmpty()) throw IllegalStateException("openrouter_video_unavailable")
        return requestVideoByModelIds(
            modelIds = models,
            apiKeys = keys,
            references = references,
            motion = motion,
            onStatus = onStatus,
        )
    }

    private suspend fun requestImageByModelIds(
        modelIds: List<String>,
        apiKeys: List<ApiKeyRecord>,
        references: JSONArray,
        garmentDescription: String,
    ): GeneratedTryOnImage {
        var lastError: Throwable? = null
        for (modelId in modelIds) {
            for (credential in apiKeys) {
                try {
                    return requestImage(
                        endpoint = OpenRouterCatalogRepository.IMAGES_URL,
                        apiKey = credential.secret,
                        model = modelId,
                        references = references,
                        garmentDescription = garmentDescription,
                        openRouter = true,
                    )
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }
        throw IllegalStateException("openrouter_image_fallback_exhausted", lastError)
    }

    private suspend fun requestVideoByModelIds(
        modelIds: List<String>,
        apiKeys: List<ApiKeyRecord>,
        references: JSONArray,
        motion: MotionDirection,
        onStatus: (VideoGenerationStatus) -> Unit,
    ): GeneratedTryOnVideo {
        var lastError: Throwable? = null
        for (modelId in modelIds) {
            for (credential in apiKeys) {
                try {
                    return requestVideo(
                        endpoint = OpenRouterCatalogRepository.VIDEOS_URL,
                        baseUrl = OpenRouterCatalogRepository.BASE_URL,
                        apiKey = credential.secret,
                        model = modelId,
                        references = references,
                        motion = motion,
                        openRouter = true,
                        onStatus = onStatus,
                    )
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }
        throw IllegalStateException("openrouter_video_fallback_exhausted", lastError)
    }

    private suspend fun requestImage(
        endpoint: String,
        apiKey: String,
        model: String,
        references: JSONArray,
        garmentDescription: String,
        openRouter: Boolean,
    ): GeneratedTryOnImage {
        val request = JSONObject()
            .put("model", model)
            .put("prompt", buildTryOnPrompt(garmentDescription))
            .put("n", 1)
            .put("input_references", references)

        if (openRouter) request.put("provider", openRouterProviderRouting())

        val response = networkClient().post(endpoint) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            if (openRouter) applyOpenRouterHeaders()
            setBody(request.toString())
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw IllegalStateException(extractApiError(body, response.status.value))

        val root = JSONObject(body)
        val first = root.optJSONArray("data")?.optJSONObject(0)
            ?: throw IllegalStateException("empty_image_response")
        val mediaType = first.optString("media_type").ifBlank { "image/png" }
        val bytes = when {
            first.optString("b64_json").isNotBlank() -> Base64.decode(first.optString("b64_json"), Base64.DEFAULT)
            first.optString("url").isNotBlank() -> downloadBytes(first.optString("url"), apiKey = null)
            else -> throw IllegalStateException("empty_image_response")
        }
        if (bytes.isEmpty()) throw IllegalStateException("empty_image_bytes")

        return GeneratedTryOnImage(
            uri = saveImage(bytes, mediaType),
            model = model,
            costUsd = root.optJSONObject("usage")?.optDouble("cost")?.takeIf { !it.isNaN() },
        )
    }

    private suspend fun requestVideo(
        endpoint: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        references: JSONArray,
        motion: MotionDirection,
        openRouter: Boolean,
        onStatus: (VideoGenerationStatus) -> Unit,
    ): GeneratedTryOnVideo {
        val request = JSONObject()
            .put("model", model)
            .put("prompt", buildVideoPrompt(motion))
            .put("input_references", references)

        if (openRouter) request.put("provider", openRouterProviderRouting())

        onStatus(VideoGenerationStatus.SUBMITTING)
        val submitResponse = networkClient().post(endpoint) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            if (openRouter) applyOpenRouterHeaders()
            setBody(request.toString())
        }
        val submitBody = submitResponse.bodyAsText()
        if (!submitResponse.status.isSuccess()) {
            throw IllegalStateException(extractApiError(submitBody, submitResponse.status.value))
        }

        val submitted = JSONObject(submitBody)
        val jobId = submitted.optString("id")
        require(jobId.isNotBlank()) { "missing_video_job" }
        var pollingUrl = submitted.optString("polling_url")
        if (pollingUrl.isBlank()) pollingUrl = "$endpoint/$jobId"
        pollingUrl = resolveRelatedUrl(baseUrl, pollingUrl)

        var completed: JSONObject? = null
        var attempt = 0
        while (attempt < MAX_VIDEO_POLLS && completed == null) {
            if (attempt > 0) delay(VIDEO_POLL_INTERVAL_MS)
            onStatus(VideoGenerationStatus.PROCESSING)
            val pollResponse = networkClient().get(pollingUrl) {
                if (sameOrigin(pollingUrl, baseUrl)) bearerAuth(apiKey)
                if (openRouter) applyOpenRouterHeaders()
            }
            val pollBody = pollResponse.bodyAsText()
            if (!pollResponse.status.isSuccess()) {
                throw IllegalStateException(extractApiError(pollBody, pollResponse.status.value))
            }
            val job = JSONObject(pollBody)
            when (job.optString("status").lowercase()) {
                "completed" -> completed = job
                "failed", "cancelled", "expired" -> throw IllegalStateException(extractJobError(job))
            }
            attempt++
        }

        val job = completed ?: throw IllegalStateException("video_timeout")
        onStatus(VideoGenerationStatus.DOWNLOADING)
        val unsignedUrls = job.optJSONArray("unsigned_urls")
        var downloadUrl = if (unsignedUrls != null && unsignedUrls.length() > 0) {
            unsignedUrls.optString(0)
        } else {
            "$endpoint/$jobId/content?index=0"
        }
        downloadUrl = resolveRelatedUrl(baseUrl, downloadUrl)
        val bytes = downloadBytes(
            url = downloadUrl,
            apiKey = apiKey.takeIf { sameOrigin(downloadUrl, baseUrl) },
        )
        if (bytes.isEmpty()) throw IllegalStateException("empty_video_bytes")

        return GeneratedTryOnVideo(
            uri = saveVideo(bytes),
            model = model,
            costUsd = job.optJSONObject("usage")?.optDouble("cost")?.takeIf { !it.isNaN() },
        )
    }

    private fun requireCustomImageConfig(): CustomAiConfig {
        val secret = apiKeyVault.activeCustomProviderKey()?.secret
            ?: throw IllegalStateException("custom_api_key_missing")
        val config = preferences.currentCustomAiConfig().copy(apiKey = secret)
        if (!config.canGenerateImages) throw IllegalStateException("custom_image_config_missing")
        return config
    }

    private fun requireCustomVideoConfig(): CustomAiConfig {
        val secret = apiKeyVault.activeCustomProviderKey()?.secret
            ?: throw IllegalStateException("custom_api_key_missing")
        val config = preferences.currentCustomAiConfig().copy(apiKey = secret)
        if (!config.canGenerateVideos) throw IllegalStateException("custom_video_config_missing")
        return config
    }

    private fun requireOpenRouterApiKeys(): List<ApiKeyRecord> = apiKeyVault.activeOpenRouterKeys().ifEmpty {
        throw IllegalStateException("openrouter_api_key_missing")
    }

    private suspend fun imageReference(source: String): JSONObject =
        JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", sourceToDataUrl(source)))

    private suspend fun sourceToDataUrl(source: String): String {
        if (source.startsWith("data:image/")) return source
        val (bytes, mimeType) = when {
            source.startsWith("https://") || source.startsWith("http://") -> {
                val response = networkClient().get(source)
                if (!response.status.isSuccess()) throw IllegalStateException("reference_download_failed")
                val bytes = response.body<ByteArray>()
                val mime = response.headers[HttpHeaders.ContentType]
                    ?.substringBefore(';')
                    ?.takeIf { it.startsWith("image/") }
                    ?: guessImageMime(source)
                bytes to mime
            }
            else -> {
                val uri = Uri.parse(source)
                val resolver = context.contentResolver
                val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") }
                    ?: guessImageMime(source)
                val bytes = when (uri.scheme) {
                    "content", "android.resource" -> resolver.openInputStream(uri)?.use { it.readBytes() }
                    "file" -> File(requireNotNull(uri.path)).readBytes()
                    else -> File(source).takeIf { it.exists() }?.readBytes()
                } ?: throw IllegalStateException("reference_read_failed")
                bytes to mime
            }
        }
        if (bytes.isEmpty()) throw IllegalStateException("reference_empty")
        if (bytes.size > MAX_REFERENCE_BYTES) throw IllegalStateException("reference_too_large")
        return "data:$mimeType;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private suspend fun downloadBytes(url: String, apiKey: String?): ByteArray {
        val response = networkClient().get(url) { apiKey?.let { bearerAuth(it) } }
        if (!response.status.isSuccess()) throw IllegalStateException("download_failed_${response.status.value}")
        return response.body()
    }

    private fun openRouterProviderRouting(): JSONObject = JSONObject()
        .put("allow_fallbacks", true)
        .put("sort", "throughput")

    private fun io.ktor.client.request.HttpRequestBuilder.applyOpenRouterHeaders() {
        header("HTTP-Referer", "https://almi.ai")
        header("X-Title", "ALMI_AI")
    }

    private fun resolveEndpoint(baseUrl: String, endpoint: String): String {
        if (endpoint.startsWith("https://") || endpoint.startsWith("http://")) return endpoint
        return "${baseUrl.trimEnd('/')}/${endpoint.trimStart('/')}"
    }

    private fun resolveRelatedUrl(baseUrl: String, value: String): String {
        if (value.startsWith("https://") || value.startsWith("http://")) return value
        return runCatching {
            val base = URI(baseUrl)
            val origin = "${base.scheme}://${base.authority}"
            "$origin/${value.trimStart('/')}"
        }.getOrElse { resolveEndpoint(baseUrl, value) }
    }

    private fun sameOrigin(url: String, baseUrl: String): Boolean = runCatching {
        val left = URI(url)
        val right = URI(baseUrl)
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.authority.equals(right.authority, ignoreCase = true)
    }.getOrDefault(false)

    private fun isOpenRouter(baseUrl: String): Boolean = baseUrl.contains("openrouter.ai", ignoreCase = true)

    private fun saveImage(bytes: ByteArray, mediaType: String): String {
        val extension = when (mediaType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val directory = File(context.filesDir, "tryon_results").apply { mkdirs() }
        val file = File(directory, "almi_${System.currentTimeMillis()}.$extension")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    private fun saveVideo(bytes: ByteArray): String {
        val directory = File(context.filesDir, "tryon_results").apply { mkdirs() }
        val file = File(directory, "almi_${System.currentTimeMillis()}.mp4")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    private fun buildTryOnPrompt(description: String): String = """
        Create a photorealistic virtual try-on edit using exactly two visual references.
        Reference 1 is the real person. Preserve that person's identity, face, hair, skin tone, body proportions, hands, pose, camera angle and background as closely as possible.
        Reference 2 is the garment. Put that exact garment on the same person. Preserve its visible color, print, logo placement, neckline, sleeves, cut, proportions and material texture.
        Replace only the relevant clothing area. Do not beautify, slim, enlarge, reshape, age or otherwise modify the person's anatomy.
        Make the garment follow the person's pose naturally with realistic folds, shadows, occlusion and perspective. It must look physically worn, never pasted on.
        Garment context: ${description.ifBlank { "Use the garment reference exactly as shown." }}
        Keep the complete person visible when the source photo contains the full body.
        Output one high-detail realistic photo. No text, watermark, collage or before/after layout.
    """.trimIndent()

    private fun buildVideoPrompt(motion: MotionDirection): String {
        val motionText = when (motion) {
            MotionDirection.TURN -> "The person makes a slow natural quarter-turn and returns toward the camera."
            MotionDirection.WALK -> "The person takes a few natural slow steps toward the camera."
            MotionDirection.DETAIL -> "Use a subtle slow camera move while the person makes minimal natural movement to reveal garment fit and fabric."
        }
        return """
            Animate the exact person and exact outfit from the reference image into a realistic fashion clip.
            $motionText
            Preserve identity, face, body proportions, garment color, logos, print, cut and fabric details. Keep motion stable and physically realistic.
            Prefer a portrait/vertical fashion composition when the model supports it.
            No morphing, outfit changes, body reshaping, extra limbs, jump cuts, text or watermark.
        """.trimIndent()
    }

    private fun extractJobError(job: JSONObject): String {
        val error = job.opt("error")
        return when (error) {
            is JSONObject -> error.optString("message").ifBlank { error.toString() }
            is String -> error
            else -> "video_failed"
        }
    }

    private fun extractApiError(body: String, statusCode: Int): String = runCatching {
        val root = JSONObject(body)
        root.optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: root.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull() ?: "http_$statusCode"

    private fun guessImageMime(source: String): String = when {
        source.substringBefore('?').endsWith(".png", ignoreCase = true) -> "image/png"
        source.substringBefore('?').endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> "image/jpeg"
    }

    companion object {
        private const val MAX_REFERENCE_BYTES = 18 * 1024 * 1024
        private const val MAX_VIDEO_POLLS = 24
        private const val VIDEO_POLL_INTERVAL_MS = 15_000L
    }
}

data class GeneratedTryOnImage(
    val uri: String,
    val model: String,
    val costUsd: Double?,
)

data class GeneratedTryOnVideo(
    val uri: String,
    val model: String,
    val costUsd: Double?,
)

enum class MotionDirection {
    TURN,
    WALK,
    DETAIL,
}

enum class VideoGenerationStatus {
    IDLE,
    SUBMITTING,
    PROCESSING,
    DOWNLOADING,
}
