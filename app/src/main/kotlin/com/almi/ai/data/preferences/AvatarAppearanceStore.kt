package com.almi.ai.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Visual presentation chosen for the avatar. This does not alter body measurements. */
enum class AvatarPresentation { MASCULINE, FEMININE }

data class AvatarAppearance(
    val presentation: AvatarPresentation = AvatarPresentation.FEMININE,
    val hairVariant: String = "bob",
    val hairColor: String = "2C1B18",
    val skinColor: String = "F8D5C2",
    val accessoriesVariant: String = "none",
    val facialHairVariant: String = "none",
    val eyesVariant: String = "default",
    val eyebrowsVariant: String = "default",
    val mouthVariant: String = "smile",
    val seed: String = "almi-avatar-v7",
) {
    /**
     * Image-based live preview. DiceBear renders deterministic PNGs and every option card uses the
     * exact same seed, so changing one selection visibly changes only that requested feature.
     */
    fun previewUrl(size: Int = 768): String {
        val params = linkedMapOf(
            "seed" to seed,
            "size" to size.toString(),
            "backgroundColor" to "f6f3ee",
            "topVariant" to hairVariant,
            "hairColor" to hairColor,
            "skinColor" to skinColor,
            "accessoriesProbability" to if (accessoriesVariant == "none") "0" else "100",
            "facialHairProbability" to if (facialHairVariant == "none") "0" else "100",
            "eyesVariant" to eyesVariant,
            "eyebrowsVariant" to eyebrowsVariant,
            "mouthVariant" to mouthVariant,
        )
        if (accessoriesVariant != "none") params["accessoriesVariant"] = accessoriesVariant
        if (facialHairVariant != "none") params["facialHairVariant"] = facialHairVariant

        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }
        return "https://api.dicebear.com/10.x/avataaars/png?$query"
    }
}

@Singleton
class AvatarAppearanceStore @Inject constructor(
    @ApplicationContext context: Context,
    private val bodyProfileStore: BodyProfileStore,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _appearance = MutableStateFlow(read())
    val appearance: StateFlow<AvatarAppearance> = _appearance.asStateFlow()

    fun setPresentation(value: AvatarPresentation) = update {
        val presetHair = when (value) {
            AvatarPresentation.FEMININE -> if (it.hairVariant in masculineHair) "bob" else it.hairVariant
            AvatarPresentation.MASCULINE -> if (it.hairVariant in feminineHair) "shortFlat" else it.hairVariant
        }
        it.copy(
            presentation = value,
            hairVariant = presetHair,
            facialHairVariant = if (value == AvatarPresentation.FEMININE) "none" else it.facialHairVariant,
        )
    }

    fun setHairVariant(value: String) = update { it.copy(hairVariant = value) }
    fun setHairColor(value: String) = update { it.copy(hairColor = value) }
    fun setSkinColor(value: String) = update { it.copy(skinColor = value) }
    fun setAccessoriesVariant(value: String) = update { it.copy(accessoriesVariant = value) }
    fun setFacialHairVariant(value: String) = update { it.copy(facialHairVariant = value) }
    fun setEyesVariant(value: String) = update { it.copy(eyesVariant = value) }
    fun setEyebrowsVariant(value: String) = update { it.copy(eyebrowsVariant = value) }
    fun setMouthVariant(value: String) = update { it.copy(mouthVariant = value) }

    fun randomizeIdentity() = update { current ->
        current.copy(seed = "almi-avatar-${System.currentTimeMillis()}")
    }

    fun currentPromptContext(): String? {
        if (bodyProfileStore.journeyMode.value != JourneyMode.AVATAR) return null
        val current = _appearance.value
        return buildString {
            append("Avatar appearance chosen by the user: ")
            append("presentation=${current.presentation.name.lowercase()}, ")
            append("hair=${current.hairVariant}, hairColor=#${current.hairColor}, ")
            append("skinTone=#${current.skinColor}, glasses=${current.accessoriesVariant}, ")
            append("facialHair=${current.facialHairVariant}, eyes=${current.eyesVariant}, ")
            append("eyebrows=${current.eyebrowsVariant}, mouth=${current.mouthVariant}. ")
            append("Preserve these appearance choices while keeping body proportions from the digital twin. ")
            append("Do not change body measurements to match the face or hairstyle.")
        }
    }

    private fun update(transform: (AvatarAppearance) -> AvatarAppearance) {
        val next = transform(_appearance.value)
        preferences.edit()
            .putString(KEY_PRESENTATION, next.presentation.name)
            .putString(KEY_HAIR, next.hairVariant)
            .putString(KEY_HAIR_COLOR, next.hairColor)
            .putString(KEY_SKIN, next.skinColor)
            .putString(KEY_ACCESSORIES, next.accessoriesVariant)
            .putString(KEY_FACIAL_HAIR, next.facialHairVariant)
            .putString(KEY_EYES, next.eyesVariant)
            .putString(KEY_EYEBROWS, next.eyebrowsVariant)
            .putString(KEY_MOUTH, next.mouthVariant)
            .putString(KEY_SEED, next.seed)
            .apply()
        _appearance.value = next
    }

    private fun read(): AvatarAppearance = AvatarAppearance(
        presentation = preferences.getString(KEY_PRESENTATION, null)
            ?.let { runCatching { AvatarPresentation.valueOf(it) }.getOrNull() }
            ?: AvatarPresentation.FEMININE,
        hairVariant = preferences.getString(KEY_HAIR, "bob") ?: "bob",
        hairColor = preferences.getString(KEY_HAIR_COLOR, "2C1B18") ?: "2C1B18",
        skinColor = preferences.getString(KEY_SKIN, "F8D5C2") ?: "F8D5C2",
        accessoriesVariant = preferences.getString(KEY_ACCESSORIES, "none") ?: "none",
        facialHairVariant = preferences.getString(KEY_FACIAL_HAIR, "none") ?: "none",
        eyesVariant = preferences.getString(KEY_EYES, "default") ?: "default",
        eyebrowsVariant = preferences.getString(KEY_EYEBROWS, "default") ?: "default",
        mouthVariant = preferences.getString(KEY_MOUTH, "smile") ?: "smile",
        seed = preferences.getString(KEY_SEED, "almi-avatar-v7") ?: "almi-avatar-v7",
    )

    companion object {
        private const val PREFS = "almi_avatar_appearance_v7"
        private const val KEY_PRESENTATION = "presentation"
        private const val KEY_HAIR = "hair"
        private const val KEY_HAIR_COLOR = "hair_color"
        private const val KEY_SKIN = "skin"
        private const val KEY_ACCESSORIES = "accessories"
        private const val KEY_FACIAL_HAIR = "facial_hair"
        private const val KEY_EYES = "eyes"
        private const val KEY_EYEBROWS = "eyebrows"
        private const val KEY_MOUTH = "mouth"
        private const val KEY_SEED = "seed"

        private val masculineHair = setOf("shortFlat", "shortRound", "shortCurly", "shortWaved", "theCaesar", "sides")
        private val feminineHair = setOf("longButNotTooLong", "bob", "curvy", "straight01", "straight02", "bigHair", "bun")
    }
}
