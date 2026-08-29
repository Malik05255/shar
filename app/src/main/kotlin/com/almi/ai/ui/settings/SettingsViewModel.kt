package com.almi.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almi.ai.data.preferences.AiMode
import com.almi.ai.data.preferences.AlmiPreferences
import com.almi.ai.data.preferences.ApiKeyRecord
import com.almi.ai.data.preferences.ApiKeyVault
import com.almi.ai.data.preferences.AppThemeMode
import com.almi.ai.data.preferences.CustomAiConfig
import com.almi.ai.data.preferences.GoogleAiStudioSettings
import com.almi.ai.data.preferences.GoogleAiStudioStore
import com.almi.ai.data.preferences.OpenRouterConfig
import com.almi.ai.data.repository.GoogleAiStudioCatalog
import com.almi.ai.data.repository.GoogleAiStudioModelInfo
import com.almi.ai.data.repository.GoogleAiStudioRepository
import com.almi.ai.data.repository.GoogleOutputKind
import com.almi.ai.data.repository.ModelCapability
import com.almi.ai.data.repository.OpenRouterCatalog
import com.almi.ai.data.repository.OpenRouterCatalogRepository
import com.almi.ai.data.repository.OpenRouterKeyStatus
import com.almi.ai.data.repository.OpenRouterOAuthRepository
import com.almi.ai.data.repository.ProviderDiscoveryRepository
import com.almi.ai.data.repository.ProviderDiscoveryResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AlmiPreferences,
    private val apiKeyVault: ApiKeyVault,
    private val openRouterOAuthRepository: OpenRouterOAuthRepository,
    private val openRouterCatalogRepository: OpenRouterCatalogRepository,
    private val providerDiscoveryRepository: ProviderDiscoveryRepository,
    private val googleAiStudioRepository: GoogleAiStudioRepository,
    private val googleAiStudioStore: GoogleAiStudioStore,
) : ViewModel() {
    val language: StateFlow<String> = preferences.language
    val themeMode: StateFlow<AppThemeMode> = preferences.themeMode
    val aiMode: StateFlow<AiMode> = preferences.aiMode
    val openRouterConfig: StateFlow<OpenRouterConfig> = preferences.openRouterConfig
    val apiKeys: StateFlow<List<ApiKeyRecord>> = apiKeyVault.openRouterKeys
    val googleAiStudioSettings: StateFlow<GoogleAiStudioSettings> = googleAiStudioStore.settings

    private val _customAiConfig = MutableStateFlow(resolvedCustomConfig())
    val customAiConfig: StateFlow<CustomAiConfig> = _customAiConfig.asStateFlow()

    private val _openRouterState = MutableStateFlow(OpenRouterUiState())
    val openRouterState: StateFlow<OpenRouterUiState> = _openRouterState.asStateFlow()

    private val _oauthState = MutableStateFlow(OAuthConnectionState())
    val oauthState: StateFlow<OAuthConnectionState> = _oauthState.asStateFlow()

    private val _providerDiscoveryState = MutableStateFlow(ProviderDiscoveryUiState())
    val providerDiscoveryState: StateFlow<ProviderDiscoveryUiState> = _providerDiscoveryState.asStateFlow()

    private val _googleAiStudioState = MutableStateFlow(GoogleAiStudioUiState())
    val googleAiStudioState: StateFlow<GoogleAiStudioUiState> = _googleAiStudioState.asStateFlow()

    init {
        if (preferences.currentAiMode() == AiMode.OPENROUTER) refreshOpenRouter()
        if (preferences.currentAiMode() == AiMode.FREE_AUTO) discoverFreeProviders()
        if (googleAiStudioStore.settings.value.connected) refreshGoogleAiStudio()
    }

    fun setLanguage(language: String) = preferences.setLanguage(language)
    fun setThemeMode(mode: AppThemeMode) = preferences.setThemeMode(mode)

    fun activateOpenRouter() {
        googleAiStudioStore.setActive(false)
        preferences.setAiMode(AiMode.OPENROUTER)
        refreshOpenRouter()
    }

    fun setOpenRouterFreeOnly(freeOnly: Boolean) {
        googleAiStudioStore.setActive(false)
        val current = preferences.currentOpenRouterConfig()
        preferences.setOpenRouterConfig(current.copy(freeOnly = freeOnly))
        preferences.setAiMode(AiMode.OPENROUTER)
        refreshOpenRouter()
    }

    fun selectOpenRouterModel(capability: ModelCapability, modelId: String) {
        googleAiStudioStore.setActive(false)
        val current = preferences.currentOpenRouterConfig()
        val updated = when (capability) {
            ModelCapability.TEXT -> current.copy(analysisModel = modelId)
            ModelCapability.IMAGE -> current.copy(imageModel = modelId)
            ModelCapability.VIDEO -> current.copy(videoModel = modelId)
        }
        preferences.setOpenRouterConfig(updated)
        preferences.setAiMode(AiMode.OPENROUTER)
    }

    fun connectOpenRouterAutomatically() {
        if (_oauthState.value.isConnecting) return
        viewModelScope.launch {
            _oauthState.value = OAuthConnectionState(isConnecting = true)
            openRouterOAuthRepository.connect()
                .onSuccess { result ->
                    apiKeyVault.addOpenRouterKey(
                        secret = result.apiKey,
                        label = result.userId?.let { "OpenRouter ${it.takeLast(6)}" } ?: "OpenRouter OAuth",
                    )
                    googleAiStudioStore.setActive(false)
                    preferences.setOpenRouterConfig(
                        preferences.currentOpenRouterConfig().copy(freeOnly = true)
                    )
                    preferences.setAiMode(AiMode.OPENROUTER)
                    _oauthState.value = OAuthConnectionState(connected = true)
                    refreshOpenRouter()
                }
                .onFailure { error ->
                    _oauthState.value = OAuthConnectionState(error = error.message ?: "oauth_failed")
                }
        }
    }

    fun addManualOpenRouterKey(value: String, freeOnly: Boolean) {
        if (value.isBlank()) return
        apiKeyVault.addOpenRouterKey(value, "OpenRouter manual")
        googleAiStudioStore.setActive(false)
        preferences.setOpenRouterConfig(preferences.currentOpenRouterConfig().copy(freeOnly = freeOnly))
        preferences.setAiMode(AiMode.OPENROUTER)
        refreshOpenRouter()
    }

    fun removeApiKey(id: String) {
        apiKeyVault.remove(id)
        refreshOpenRouter()
    }

    fun setApiKeyEnabled(id: String, enabled: Boolean) {
        apiKeyVault.setEnabled(id, enabled)
        refreshOpenRouter()
    }

    fun clearOAuthMessage() {
        _oauthState.value = OAuthConnectionState()
    }

    fun refreshOpenRouter() {
        viewModelScope.launch {
            _openRouterState.value = _openRouterState.value.copy(isLoading = true, error = null)
            val key = apiKeyVault.activeOpenRouterKeys().firstOrNull()?.secret
            val catalogResult = openRouterCatalogRepository.loadCatalog(key)
            val keyStatus = key?.let { openRouterCatalogRepository.loadKeyStatus(it).getOrNull() }
            catalogResult
                .onSuccess { catalog ->
                    _openRouterState.value = OpenRouterUiState(
                        isLoading = false,
                        catalog = catalog,
                        keyStatus = keyStatus,
                        lastUpdatedAt = System.currentTimeMillis(),
                    )
                }
                .onFailure { error ->
                    _openRouterState.value = OpenRouterUiState(
                        isLoading = false,
                        keyStatus = keyStatus,
                        error = error.message ?: "openrouter_catalog_failed",
                    )
                }
        }
    }

    fun connectGoogleAiStudio(apiKey: String) {
        if (_googleAiStudioState.value.isConnecting || apiKey.isBlank()) return
        viewModelScope.launch {
            _googleAiStudioState.value = GoogleAiStudioUiState(isConnecting = true)
            googleAiStudioRepository.connect(apiKey)
                .onSuccess { catalog ->
                    googleAiStudioStore.saveApiKey(apiKey)
                    googleAiStudioStore.setConnected(true)
                    _googleAiStudioState.value = GoogleAiStudioUiState(
                        connected = true,
                        catalog = catalog,
                        lastUpdatedAt = System.currentTimeMillis(),
                    )
                }
                .onFailure { error ->
                    googleAiStudioStore.setConnected(false)
                    _googleAiStudioState.value = GoogleAiStudioUiState(
                        connected = false,
                        error = error.message ?: "google_connect_failed",
                    )
                }
        }
    }

    fun refreshGoogleAiStudio() {
        val key = googleAiStudioStore.apiKey()
        if (key.isBlank() || _googleAiStudioState.value.isConnecting) return
        viewModelScope.launch {
            _googleAiStudioState.value = _googleAiStudioState.value.copy(isConnecting = true, error = null)
            googleAiStudioRepository.connect(key)
                .onSuccess { catalog ->
                    googleAiStudioStore.setConnected(true)
                    _googleAiStudioState.value = GoogleAiStudioUiState(
                        connected = true,
                        catalog = catalog,
                        lastUpdatedAt = System.currentTimeMillis(),
                    )
                }
                .onFailure { error ->
                    googleAiStudioStore.setConnected(false)
                    googleAiStudioStore.setActive(false)
                    _googleAiStudioState.value = GoogleAiStudioUiState(error = error.message ?: "google_refresh_failed")
                }
        }
    }

    fun selectGoogleFreeModel(model: GoogleAiStudioModelInfo) {
        googleAiStudioStore.selectFreeModel(model.id)
        activateGoogleModel(model, paid = false)
    }

    fun selectGooglePaidModel(model: GoogleAiStudioModelInfo) {
        googleAiStudioStore.selectPaidModel(model.id)
        activateGoogleModel(model, paid = true)
    }

    private fun activateGoogleModel(model: GoogleAiStudioModelInfo, paid: Boolean) {
        when (model.outputKind) {
            GoogleOutputKind.TEXT -> googleAiStudioStore.selectTextModel(model.id, paid)
            GoogleOutputKind.IMAGE -> googleAiStudioStore.selectImageModel(model.id, paid)
            GoogleOutputKind.VIDEO -> googleAiStudioStore.selectVideoModel(model.id, paid)
        }
    }

    fun disconnectGoogleAiStudio() {
        googleAiStudioStore.clearConnection()
        _googleAiStudioState.value = GoogleAiStudioUiState()
    }

    fun saveAndActivateCustom(config: CustomAiConfig) {
        googleAiStudioStore.setActive(false)
        apiKeyVault.setCustomProviderKey(
            secret = config.apiKey,
            label = config.providerName.ifBlank { "Custom provider" },
        )
        preferences.setCustomAiConfig(config)
        _customAiConfig.value = resolvedCustomConfig()
        preferences.setAiMode(AiMode.CUSTOM)
    }

    fun setFreeMode(enabled: Boolean) {
        googleAiStudioStore.setActive(false)
        preferences.setAiMode(if (enabled) AiMode.FREE_AUTO else AiMode.OPENROUTER)
        if (enabled) discoverFreeProviders()
    }

    fun discoverFreeProviders() {
        if (_providerDiscoveryState.value.isChecking) return
        viewModelScope.launch {
            _providerDiscoveryState.value = _providerDiscoveryState.value.copy(
                isChecking = true,
                error = null,
            )
            providerDiscoveryRepository.discoverTop(10)
                .onSuccess { result ->
                    val connected = result.connectedProvider
                    _providerDiscoveryState.value = ProviderDiscoveryUiState(
                        isChecking = false,
                        result = result,
                        activeProviderId = connected?.id,
                    )
                }
                .onFailure { error ->
                    _providerDiscoveryState.value = ProviderDiscoveryUiState(
                        isChecking = false,
                        error = error.message ?: "provider_discovery_failed",
                    )
                }
        }
    }

    fun activateDiscoveredProvider(providerId: String): Boolean {
        val provider = _providerDiscoveryState.value.result.providers.firstOrNull { it.id == providerId }
            ?: return false
        if (!provider.connected || !provider.integrated || provider.requiresPersonalApiKey) return false
        googleAiStudioStore.setActive(false)
        _providerDiscoveryState.value = _providerDiscoveryState.value.copy(activeProviderId = provider.id)
        preferences.setAiMode(AiMode.FREE_AUTO)
        return true
    }

    private fun resolvedCustomConfig(): CustomAiConfig =
        preferences.currentCustomAiConfig().copy(
            apiKey = apiKeyVault.activeCustomProviderKey()?.secret.orEmpty(),
        )
}

data class OpenRouterUiState(
    val isLoading: Boolean = false,
    val catalog: OpenRouterCatalog = OpenRouterCatalog(),
    val keyStatus: OpenRouterKeyStatus? = null,
    val lastUpdatedAt: Long = 0L,
    val error: String? = null,
)

data class GoogleAiStudioUiState(
    val isConnecting: Boolean = false,
    val connected: Boolean = false,
    val catalog: GoogleAiStudioCatalog = GoogleAiStudioCatalog(),
    val lastUpdatedAt: Long = 0L,
    val error: String? = null,
)

data class ProviderDiscoveryUiState(
    val isChecking: Boolean = false,
    val result: ProviderDiscoveryResult = ProviderDiscoveryResult(),
    val activeProviderId: String? = null,
    val error: String? = null,
)

data class OAuthConnectionState(
    val isConnecting: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
)
