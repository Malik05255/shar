package com.almi.ai.ui.tryon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almi.ai.data.model.ProductPreview
import com.almi.ai.data.preferences.AvatarAppearanceStore
import com.almi.ai.data.preferences.BodyProfileStore
import com.almi.ai.data.preferences.GoogleAiStudioStore
import com.almi.ai.data.repository.GoogleMediaGenerationGateway
import com.almi.ai.data.repository.MediaGenerationGateway
import com.almi.ai.data.repository.MotionDirection
import com.almi.ai.data.repository.ProductPreviewRepository
import com.almi.ai.data.repository.VideoGenerationStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.exp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class TryOnViewModel @Inject constructor(
    private val productRepository: ProductPreviewRepository,
    private val generationGateway: MediaGenerationGateway,
    private val googleGenerationGateway: GoogleMediaGenerationGateway,
    private val googleAiStudioStore: GoogleAiStudioStore,
    private val bodyProfileStore: BodyProfileStore,
    private val avatarAppearanceStore: AvatarAppearanceStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TryOnUiState())
    val uiState: StateFlow<TryOnUiState> = _uiState.asStateFlow()

    private var estimatedImageGenerationMs = DEFAULT_IMAGE_GENERATION_ESTIMATE_MS

    fun setPersonImage(uri: String) = updateInputs { it.copy(personImage = uri) }
    fun setGarmentImage(uri: String) = updateInputs {
        it.copy(garmentImage = uri, productImage = null, productError = ProductError.NONE)
    }

    fun setProductUrl(value: String) {
        _uiState.update { it.copy(productUrl = value, productError = ProductError.NONE) }
    }

    fun setGarmentSize(size: GarmentSize) {
        _uiState.update { current ->
            current.copy(
                selectedGarmentSize = size,
                fitSimulation = buildFitSimulation(current, size),
                generatedImage = null,
                generatedVideo = null,
                imageProgress = 0f,
                imageError = GenerationError.NONE,
            )
        }
    }

    fun setMotion(direction: MotionDirection) {
        _uiState.update { it.copy(motion = direction, generatedVideo = null, videoError = false) }
    }

    fun loadProduct() {
        val url = _uiState.value.productUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(productError = ProductError.EMPTY_URL) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingProduct = true, productError = ProductError.NONE) }
            productRepository.load(url)
                .onSuccess(::applyProduct)
                .onFailure {
                    _uiState.update {
                        it.copy(isLoadingProduct = false, productError = ProductError.UNAVAILABLE)
                    }
                }
        }
    }

    fun generateImage() {
        val state = _uiState.value
        val person = state.personImage ?: return
        val garment = state.effectiveGarmentImage ?: return
        val bodyContext = bodyProfileStore.currentPromptContext()
        val avatarContext = avatarAppearanceStore.currentPromptContext()
        val fit = state.selectedGarmentSize?.let { buildFitSimulation(state, it) }
        if (fit != state.fitSimulation) {
            _uiState.update { it.copy(fitSimulation = fit) }
        }
        val generationDescription = listOfNotNull(
            state.productTitle.takeIf(String::isNotBlank),
            bodyContext,
            avatarContext,
            fit?.promptContext,
        ).joinToString("\n")

        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    isGeneratingImage = true,
                    imageProgress = 0f,
                    imageError = GenerationError.NONE,
                    generatedImage = null,
                    generatedVideo = null,
                    videoError = false,
                )
            }

            val progressJob = launch {
                delay(150)
                while (isActive) {
                    val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                    val estimate = estimatedImageGenerationMs.coerceAtLeast(MIN_IMAGE_GENERATION_ESTIMATE_MS)
                    val normalized = elapsed.toDouble() / estimate.toDouble()
                    val curve = 1.0 - exp(-normalized * 2.25)
                    val target = (0.02 + curve * 0.90).toFloat().coerceIn(0.02f, 0.92f)
                    _uiState.update { current ->
                        if (!current.isGeneratingImage) current
                        else current.copy(imageProgress = maxOf(current.imageProgress, target))
                    }
                    delay(PROGRESS_TICK_MS)
                }
            }

            val googleSettings = googleAiStudioStore.settings.value
            val result = if (googleSettings.active && googleSettings.imageModelId.isNotBlank()) {
                googleGenerationGateway.generateImage(
                    personImage = person,
                    garmentImage = garment,
                    garmentDescription = generationDescription,
                )
            } else {
                generationGateway.generateImage(
                    personImage = person,
                    garmentImage = garment,
                    garmentDescription = generationDescription,
                )
            }

            result.onSuccess { generated ->
                progressJob.cancel()
                val actualDuration = (System.currentTimeMillis() - startedAt)
                    .coerceIn(MIN_IMAGE_GENERATION_ESTIMATE_MS, MAX_IMAGE_GENERATION_ESTIMATE_MS)
                estimatedImageGenerationMs = (
                    estimatedImageGenerationMs * ESTIMATE_HISTORY_WEIGHT +
                        actualDuration * ESTIMATE_LATEST_WEIGHT
                    ).toLong()

                _uiState.update { current ->
                    if (current.isGeneratingImage) current.copy(imageProgress = 1f) else current
                }
                delay(COMPLETED_PROGRESS_HOLD_MS)
                _uiState.update { current ->
                    if (!current.isGeneratingImage) current
                    else current.copy(
                        isGeneratingImage = false,
                        imageProgress = 1f,
                        generatedImage = generated.uri,
                    )
                }
            }.onFailure { error ->
                progressJob.cancel()
                _uiState.update {
                    it.copy(
                        isGeneratingImage = false,
                        imageProgress = 0f,
                        imageError = classifyGenerationError(error),
                    )
                }
            }
        }
    }

    fun generateVideo() {
        val image = _uiState.value.generatedImage ?: return
        val motion = _uiState.value.motion
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGeneratingVideo = true,
                    videoError = false,
                    videoStatus = VideoGenerationStatus.SUBMITTING,
                    generatedVideo = null,
                )
            }

            val googleSettings = googleAiStudioStore.settings.value
            val result = if (googleSettings.active && googleSettings.videoModelId.isNotBlank()) {
                googleGenerationGateway.generateVideo(image, motion) { status ->
                    _uiState.update { it.copy(videoStatus = status) }
                }
            } else {
                generationGateway.generateVideo(image, motion) { status ->
                    _uiState.update { it.copy(videoStatus = status) }
                }
            }

            result.onSuccess { generated ->
                _uiState.update {
                    it.copy(
                        isGeneratingVideo = false,
                        videoStatus = VideoGenerationStatus.IDLE,
                        generatedVideo = generated.uri,
                    )
                }
            }.onFailure {
                _uiState.update {
                    it.copy(
                        isGeneratingVideo = false,
                        videoStatus = VideoGenerationStatus.IDLE,
                        videoError = true,
                    )
                }
            }
        }
    }

    fun returnToStudio() {
        _uiState.update {
            it.copy(
                generatedImage = null,
                generatedVideo = null,
                isGeneratingVideo = false,
                videoStatus = VideoGenerationStatus.IDLE,
                videoError = false,
                imageProgress = 0f,
                imageError = GenerationError.NONE,
            )
        }
    }

    fun reset() {
        _uiState.value = TryOnUiState()
    }

    private fun applyProduct(preview: ProductPreview) {
        _uiState.update { current ->
            val updated = current.copy(
                isLoadingProduct = false,
                productUrl = preview.sourceUrl,
                productTitle = preview.title,
                productDescription = preview.description,
                productBrand = preview.brand,
                productPrice = preview.price,
                productCurrency = preview.currency,
                productColor = preview.color,
                productSku = preview.sku,
                merchant = preview.merchant,
                productImage = preview.imageUrl,
                garmentImage = null,
                productError = if (preview.imageUrl == null) ProductError.IMAGE_NOT_FOUND else ProductError.NONE,
                generatedImage = null,
                generatedVideo = null,
                imageProgress = 0f,
            )
            updated.copy(
                fitSimulation = updated.selectedGarmentSize?.let { size -> buildFitSimulation(updated, size) },
            )
        }
    }

    private fun buildFitSimulation(state: TryOnUiState, size: GarmentSize): FitSimulation =
        GarmentFitSimulationEngine.simulate(
            profile = bodyProfileStore.profile.value,
            size = size,
            garmentMeasurements = state.sizeMeasurements[size],
            productTitle = state.productTitle,
            brand = state.productBrand,
        )

    private fun updateInputs(update: (TryOnUiState) -> TryOnUiState) {
        _uiState.update {
            update(it).copy(
                generatedImage = null,
                generatedVideo = null,
                imageProgress = 0f,
                imageError = GenerationError.NONE,
                videoError = false,
            )
        }
    }

    private fun classifyGenerationError(error: Throwable): GenerationError {
        val message = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty().lowercase() }
        return when {
            message.contains("free_api_key_missing") ||
                message.contains("custom_image_config_missing") ||
                message.contains("custom_config_missing") ||
                message.contains("google_api_key_missing") ||
                message.contains("google_image_model_missing") ||
                message.contains("google_not_active") -> GenerationError.API_KEY_MISSING
            else -> GenerationError.REQUEST_FAILED
        }
    }

    companion object {
        private const val DEFAULT_IMAGE_GENERATION_ESTIMATE_MS = 28_000L
        private const val MIN_IMAGE_GENERATION_ESTIMATE_MS = 6_000L
        private const val MAX_IMAGE_GENERATION_ESTIMATE_MS = 120_000L
        private const val PROGRESS_TICK_MS = 250L
        private const val COMPLETED_PROGRESS_HOLD_MS = 420L
        private const val ESTIMATE_HISTORY_WEIGHT = 0.72
        private const val ESTIMATE_LATEST_WEIGHT = 0.28
    }
}

data class TryOnUiState(
    val personImage: String? = null,
    val garmentImage: String? = null,
    val productUrl: String = "",
    val productTitle: String = "",
    val productDescription: String = "",
    val productBrand: String = "",
    val productPrice: String = "",
    val productCurrency: String = "",
    val productColor: String = "",
    val productSku: String = "",
    val productImage: String? = null,
    val merchant: String = "",
    val availableGarmentSizes: List<GarmentSize> = GarmentSize.entries,
    val selectedGarmentSize: GarmentSize? = null,
    val sizeMeasurements: Map<GarmentSize, GarmentSizeMeasurements> = emptyMap(),
    val fitSimulation: FitSimulation? = null,
    val isLoadingProduct: Boolean = false,
    val productError: ProductError = ProductError.NONE,
    val motion: MotionDirection = MotionDirection.TURN,
    val isGeneratingImage: Boolean = false,
    val imageProgress: Float = 0f,
    val generatedImage: String? = null,
    val imageError: GenerationError = GenerationError.NONE,
    val isGeneratingVideo: Boolean = false,
    val videoStatus: VideoGenerationStatus = VideoGenerationStatus.IDLE,
    val generatedVideo: String? = null,
    val videoError: Boolean = false,
) {
    val effectiveGarmentImage: String?
        get() = garmentImage ?: productImage

    val displayProductPrice: String
        get() = listOf(productPrice, productCurrency).filter(String::isNotBlank).joinToString(" ")

    val canGenerate: Boolean
        get() = personImage != null && effectiveGarmentImage != null && !isGeneratingImage
}

enum class ProductError {
    NONE,
    EMPTY_URL,
    UNAVAILABLE,
    IMAGE_NOT_FOUND,
}

enum class GenerationError {
    NONE,
    API_KEY_MISSING,
    REQUEST_FAILED,
}
