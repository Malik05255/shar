package com.almi.ai.data.repository

import com.almi.ai.data.network.NetworkClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FreeAiCatalogRepository @Inject constructor(
    private val networkClient: NetworkClient,
) {
    suspend fun discover(
        apiKey: String? = null,
        limitPerMedia: Int = MAX_CANDIDATES,
    ): Result<FreeAiCatalog> = withContext(Dispatchers.IO) {
        runCatching {
            val imageCandidates = (
                fetchEndpoint(IMAGE_MODELS_URL, MediaOutput.IMAGE, apiKey) +
                    fetchEndpoint("$GENERIC_MODELS_URL?output_modalities=image", MediaOutput.IMAGE, apiKey)
                )
                .distinctBy { it.id }
                .sortedCandidates()
                .take(limitPerMedia.coerceIn(1, MAX_CANDIDATES))

            var videoCandidates = (
                fetchEndpoint(VIDEO_MODELS_URL, MediaOutput.VIDEO, apiKey) +
                    fetchEndpoint("$GENERIC_MODELS_URL?output_modalities=video", MediaOutput.VIDEO, apiKey)
                )
                .distinctBy { it.id }
                .sortedCandidates()

            // Current public free video route. Keep it as a bootstrap candidate because
            // older catalog payloads do not always expose complete architecture metadata.
            if (videoCandidates.none { it.id.equals(KNOWN_FREE_VIDEO_MODEL, ignoreCase = true) }) {
                videoCandidates = videoCandidates + FreeAiCandidate(
                    id = KNOWN_FREE_VIDEO_MODEL,
                    name = "Alibaba: Wan 2.6 (free)",
                    created = 0L,
                    qualityScore = 120,
                )
            }

            FreeAiCatalog(
                imageModels = imageCandidates,
                videoModels = videoCandidates.sortedCandidates().take(limitPerMedia.coerceIn(1, MAX_CANDIDATES)),
            )
        }
    }

    private suspend fun fetchEndpoint(
        url: String,
        output: MediaOutput,
        apiKey: String?,
    ): List<FreeAiCandidate> {
        val response = networkClient().get(url) {
            apiKey?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
        }
        if (!response.status.isSuccess()) return emptyList()

        val body = response.bodyAsText()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()

        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                candidateFrom(item, output)?.let(::add)
            }
        }
    }

    private fun candidateFrom(item: JSONObject, output: MediaOutput): FreeAiCandidate? {
        val id = item.optString("id").trim()
        if (id.isBlank()) return null
        val name = item.optString("name").ifBlank { id }
        if (!isFree(item, id, name)) return null

        val architecture = item.optJSONObject("architecture")
        val inputModalities = architecture?.optJSONArray("input_modalities")
        val outputModalities = architecture?.optJSONArray("output_modalities")
        val supportedParameters = item.optJSONArray("supported_parameters") ?: JSONArray()

        if (outputModalities != null && outputModalities.length() > 0 &&
            !outputModalities.containsString(output.apiValue)
        ) return null

        val declaresImageInput = inputModalities?.containsString("image") == true
        val declaresReferences = supportedParameters.containsString("input_references") ||
            supportedParameters.containsString("reference_images") ||
            supportedParameters.containsString("first_frame_image") ||
            supportedParameters.containsString("image")

        // For specialized image/video catalogs metadata may omit architecture. Accept the
        // model when input metadata is absent; generation itself is the final capability test.
        if (inputModalities != null && inputModalities.length() > 0 && !declaresImageInput && !declaresReferences) {
            return null
        }

        return FreeAiCandidate(
            id = id,
            name = name,
            created = item.optLong("created", 0L),
            qualityScore = qualityScore(id, output) + if (declaresReferences) 20 else 0,
        )
    }

    private fun isFree(item: JSONObject, id: String, name: String): Boolean {
        if (id.endsWith(":free", ignoreCase = true) || name.contains("(free)", ignoreCase = true)) {
            return true
        }
        val pricing = item.optJSONObject("pricing") ?: return false
        val values = buildList {
            val iterator = pricing.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                pricing.optString(key).toDoubleOrNull()?.let(::add)
            }
        }
        return values.isNotEmpty() && values.all { it <= 0.0 }
    }

    private fun List<FreeAiCandidate>.sortedCandidates(): List<FreeAiCandidate> =
        sortedWith(
            compareByDescending<FreeAiCandidate> { it.qualityScore }
                .thenByDescending { it.created }
        )

    private fun qualityScore(id: String, output: MediaOutput): Int {
        val value = id.lowercase()
        val orderedFamilies = when (output) {
            MediaOutput.IMAGE -> listOf(
                "gemini", "gpt-image", "seedream", "qwen-image", "flux", "grok", "recraft", "krea"
            )
            MediaOutput.VIDEO -> listOf(
                "wan-3", "wan-2.7", "wan-2.6", "seedance", "veo", "kling", "grok", "hailuo", "runway"
            )
        }
        val index = orderedFamilies.indexOfFirst { value.contains(it) }
        return if (index == -1) 1 else 100 - index * 8
    }

    private fun JSONArray.containsString(value: String): Boolean {
        for (index in 0 until length()) {
            if (optString(index).equals(value, ignoreCase = true)) return true
        }
        return false
    }

    private enum class MediaOutput(val apiValue: String) {
        IMAGE("image"),
        VIDEO("video"),
    }

    companion object {
        const val MAX_CANDIDATES = 30
        const val KNOWN_FREE_VIDEO_MODEL = "alibaba/wan-2.6:free"
        private const val GENERIC_MODELS_URL = "https://openrouter.ai/api/v1/models"
        private const val IMAGE_MODELS_URL = "https://openrouter.ai/api/v1/images/models"
        private const val VIDEO_MODELS_URL = "https://openrouter.ai/api/v1/videos/models"
    }
}

data class FreeAiCatalog(
    val imageModels: List<FreeAiCandidate>,
    val videoModels: List<FreeAiCandidate>,
)

data class FreeAiCandidate(
    val id: String,
    val name: String,
    val created: Long,
    val qualityScore: Int,
)
