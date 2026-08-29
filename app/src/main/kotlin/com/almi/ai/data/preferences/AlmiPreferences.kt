package com.almi.ai.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class AiMode {
    OPENROUTER,
    CUSTOM,
    FREE_AUTO,
}

data class OpenRouterConfig(
    val freeOnly: Boolean = true,
    val analysisModel: String = DEFAULT_ANALYSIS_MODEL,
    val imageModel: String = "",
    val videoModel: String = "",
) {
    companion object {
        const val DEFAULT_ANALYSIS_MODEL = "openrouter/free"
    }
}

/**
 * Non-secret custom provider configuration. [apiKey] exists only as an in-memory/migration field;
 * new builds store the actual secret exclusively in [ApiKeyVault].
 */
data class CustomAiConfig(
    val providerName: String = "OpenRouter",
    val baseUrl: String = DEFAULT_OPENROUTER_BASE_URL,
    val apiKey: String = "",
    val analysisEndpoint: String = "/chat/completions",
    val analysisModel: String = DEFAULT_ANALYSIS_MODEL,
    val imageEndpoint: String = "/images",
    val imageModel: String = DEFAULT_IMAGE_MODEL,
    val videoEndpoint: String = "/videos",
    val videoModel: String = DEFAULT_VIDEO_MODEL,
) {
    private val hasCredentials: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    val canAnalyzeProducts: Boolean
        get() = hasCredentials && analysisEndpoint.isNotBlank() && analysisModel.isNotBlank()

    val canGenerateImages: Boolean
        get() = hasCredentials && imageEndpoint.isNotBlank() && imageModel.isNotBlank()

    val canGenerateVideos: Boolean
        get() = hasCredentials && videoEndpoint.isNotBlank() && videoModel.isNotBlank()

    val isUsable: Boolean
        get() = canGenerateImages

    companion object {
        const val DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_ANALYSIS_MODEL = "openrouter/free"
        const val DEFAULT_IMAGE_MODEL = "openai/gpt-image-1"
        const val DEFAULT_VIDEO_MODEL = "bytedance/seedance-2.0-fast"
    }
}

@Singleton
class AlmiPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(readLanguage(preferences))
    val language: StateFlow<String> = _language.asStateFlow()

    private val _themeMode = MutableStateFlow(readTheme(preferences))
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _aiMode = MutableStateFlow(readAiMode(preferences))
    val aiMode: StateFlow<AiMode> = _aiMode.asStateFlow()

    private val _openRouterConfig = MutableStateFlow(readOpenRouterConfig(preferences))
    val openRouterConfig: StateFlow<OpenRouterConfig> = _openRouterConfig.asStateFlow()

    private val _customAiConfig = MutableStateFlow(readCustomConfig(preferences))
    val customAiConfig: StateFlow<CustomAiConfig> = _customAiConfig.asStateFlow()

    private val _freeOpenRouterApiKey = MutableStateFlow(readFreeOpenRouterKey(preferences))
    val freeOpenRouterApiKey: StateFlow<String> = _freeOpenRouterApiKey.asStateFlow()

    /**
     * Runtime language switching is intentionally Compose-driven. Calling
     * AppCompatDelegate.setApplicationLocales() here would recreate MainActivity by default and
     * causes the visible black flash captured during Arabic/English switching. We persist the
     * choice and update the StateFlow immediately; MainActivity already derives LayoutDirection
     * and all visible copy from this state. The stored locale is applied once on the next cold
     * start before the activity is created so Android resources also start in the right locale.
     */
    fun setLanguage(language: String) {
        val normalized = if (language.equals("en", ignoreCase = true)) "en" else "ar"
        if (_language.value == normalized) return
        preferences.edit().putString(KEY_LANGUAGE, normalized).apply()
        _language.value = normalized
    }

    fun setThemeMode(mode: AppThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setAiMode(mode: AiMode) {
        preferences.edit().putString(KEY_AI_MODE, mode.name).apply()
        _aiMode.value = mode
    }

    fun setOpenRouterConfig(config: OpenRouterConfig) {
        val normalized = config.copy(
            analysisModel = config.analysisModel.trim(),
            imageModel = config.imageModel.trim(),
            videoModel = config.videoModel.trim(),
        )
        preferences.edit()
            .putBoolean(KEY_OPENROUTER_FREE_ONLY, normalized.freeOnly)
            .putString(KEY_OPENROUTER_ANALYSIS_MODEL, normalized.analysisModel)
            .putString(KEY_OPENROUTER_IMAGE_MODEL, normalized.imageModel)
            .putString(KEY_OPENROUTER_VIDEO_MODEL, normalized.videoModel)
            .apply()
        _openRouterConfig.value = normalized
    }

    /** Saves only non-secret provider metadata. The key is handled by ApiKeyVault. */
    fun setCustomAiConfig(config: CustomAiConfig) {
        val normalized = config.copy(
            providerName = config.providerName.trim(),
            baseUrl = config.baseUrl.trim().trimEnd('/'),
            apiKey = "",
            analysisEndpoint = normalizeEndpoint(config.analysisEndpoint),
            analysisModel = config.analysisModel.trim(),
            imageEndpoint = normalizeEndpoint(config.imageEndpoint),
            imageModel = config.imageModel.trim(),
            videoEndpoint = normalizeEndpoint(config.videoEndpoint),
            videoModel = config.videoModel.trim(),
        )
        preferences.edit()
            .remove(KEY_CUSTOM_API_KEY)
            .putString(KEY_CUSTOM_PROVIDER_NAME, normalized.providerName)
            .putString(KEY_CUSTOM_BASE_URL, normalized.baseUrl)
            .putString(KEY_CUSTOM_ANALYSIS_ENDPOINT, normalized.analysisEndpoint)
            .putString(KEY_CUSTOM_ANALYSIS_MODEL, normalized.analysisModel)
            .putString(KEY_CUSTOM_IMAGE_ENDPOINT, normalized.imageEndpoint)
            .putString(KEY_CUSTOM_IMAGE_MODEL, normalized.imageModel)
            .putString(KEY_CUSTOM_VIDEO_ENDPOINT, normalized.videoEndpoint)
            .putString(KEY_CUSTOM_VIDEO_MODEL, normalized.videoModel)
            .apply()
        _customAiConfig.value = normalized
    }

    fun currentAiMode(): AiMode = readAiMode(preferences)
    fun currentOpenRouterConfig(): OpenRouterConfig = readOpenRouterConfig(preferences)
    fun currentCustomAiConfig(): CustomAiConfig = readCustomConfig(preferences)
    fun currentFreeOpenRouterApiKey(): String = readFreeOpenRouterKey(preferences)

    /** Called after ApiKeyVault imports secrets from older ALMI_AI builds. */
    fun clearLegacyPlaintextApiKeys() {
        preferences.edit()
            .remove(KEY_CUSTOM_API_KEY)
            .remove(KEY_FREE_OPENROUTER_API_KEY)
            .remove(LEGACY_KEY_API_KEY)
            .apply()
        _customAiConfig.value = readCustomConfig(preferences)
        _freeOpenRouterApiKey.value = ""
    }

    companion object {
        private const val PREFERENCES_NAME = "almi_ai_settings"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME = "theme"
        private const val KEY_AI_MODE = "ai_mode"

        private const val KEY_OPENROUTER_FREE_ONLY = "openrouter_free_only"
        private const val KEY_OPENROUTER_ANALYSIS_MODEL = "openrouter_analysis_model"
        private const val KEY_OPENROUTER_IMAGE_MODEL = "openrouter_image_model"
        private const val KEY_OPENROUTER_VIDEO_MODEL = "openrouter_video_model"

        private const val KEY_CUSTOM_PROVIDER_NAME = "custom_provider_name"
        private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_CUSTOM_ANALYSIS_ENDPOINT = "custom_analysis_endpoint"
        private const val KEY_CUSTOM_ANALYSIS_MODEL = "custom_analysis_model"
        private const val KEY_CUSTOM_IMAGE_ENDPOINT = "custom_image_endpoint"
        private const val KEY_CUSTOM_IMAGE_MODEL = "custom_image_model"
        private const val KEY_CUSTOM_VIDEO_ENDPOINT = "custom_video_endpoint"
        private const val KEY_CUSTOM_VIDEO_MODEL = "custom_video_model"
        private const val KEY_FREE_OPENROUTER_API_KEY = "free_openrouter_api_key"

        private const val LEGACY_KEY_API_KEY = "openrouter_api_key"

        fun applyStoredLanguage(context: Context) {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val language = readLanguage(preferences)
            val requested = LocaleListCompat.forLanguageTags(language)
            if (AppCompatDelegate.getApplicationLocales() != requested) {
                AppCompatDelegate.setApplicationLocales(requested)
            }
        }

        private fun readLanguage(preferences: SharedPreferences): String =
            preferences.getString(KEY_LANGUAGE, "ar")
                ?.takeIf { it == "ar" || it == "en" }
                ?: "ar"

        private fun readTheme(preferences: SharedPreferences): AppThemeMode =
            runCatching {
                AppThemeMode.valueOf(preferences.getString(KEY_THEME, AppThemeMode.SYSTEM.name).orEmpty())
            }.getOrDefault(AppThemeMode.SYSTEM)

        private fun readAiMode(preferences: SharedPreferences): AiMode =
            runCatching {
                AiMode.valueOf(preferences.getString(KEY_AI_MODE, AiMode.OPENROUTER.name).orEmpty())
            }.getOrDefault(AiMode.OPENROUTER)

        private fun readOpenRouterConfig(preferences: SharedPreferences): OpenRouterConfig =
            OpenRouterConfig(
                freeOnly = preferences.getBoolean(KEY_OPENROUTER_FREE_ONLY, true),
                analysisModel = preferences.getString(
                    KEY_OPENROUTER_ANALYSIS_MODEL,
                    OpenRouterConfig.DEFAULT_ANALYSIS_MODEL,
                ).orEmpty(),
                imageModel = preferences.getString(KEY_OPENROUTER_IMAGE_MODEL, "").orEmpty(),
                videoModel = preferences.getString(KEY_OPENROUTER_VIDEO_MODEL, "").orEmpty(),
            )

        private fun readCustomConfig(preferences: SharedPreferences): CustomAiConfig {
            val migratedKey = preferences.getString(KEY_CUSTOM_API_KEY, null)
                ?: preferences.getString(LEGACY_KEY_API_KEY, "")
                .orEmpty()
            return CustomAiConfig(
                providerName = preferences.getString(KEY_CUSTOM_PROVIDER_NAME, "OpenRouter").orEmpty(),
                baseUrl = preferences.getString(
                    KEY_CUSTOM_BASE_URL,
                    CustomAiConfig.DEFAULT_OPENROUTER_BASE_URL,
                ).orEmpty(),
                apiKey = migratedKey,
                analysisEndpoint = preferences.getString(KEY_CUSTOM_ANALYSIS_ENDPOINT, "/chat/completions").orEmpty(),
                analysisModel = preferences.getString(
                    KEY_CUSTOM_ANALYSIS_MODEL,
                    CustomAiConfig.DEFAULT_ANALYSIS_MODEL,
                ).orEmpty(),
                imageEndpoint = preferences.getString(KEY_CUSTOM_IMAGE_ENDPOINT, "/images").orEmpty(),
                imageModel = preferences.getString(
                    KEY_CUSTOM_IMAGE_MODEL,
                    CustomAiConfig.DEFAULT_IMAGE_MODEL,
                ).orEmpty(),
                videoEndpoint = preferences.getString(KEY_CUSTOM_VIDEO_ENDPOINT, "/videos").orEmpty(),
                videoModel = preferences.getString(
                    KEY_CUSTOM_VIDEO_MODEL,
                    CustomAiConfig.DEFAULT_VIDEO_MODEL,
                ).orEmpty(),
            )
        }

        private fun readFreeOpenRouterKey(preferences: SharedPreferences): String =
            (preferences.getString(KEY_FREE_OPENROUTER_API_KEY, null)
                ?: preferences.getString(LEGACY_KEY_API_KEY, ""))
                .orEmpty()
                .trim()

        private fun normalizeEndpoint(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return ""
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
            return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        }
    }
}
