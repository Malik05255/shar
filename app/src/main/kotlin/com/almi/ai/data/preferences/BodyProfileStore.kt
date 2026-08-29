package com.almi.ai.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class JourneyMode {
    AVATAR,
    PHOTO,
}

enum class BodyMeasurePoint(val key: String) {
    // Women's dressmaking profile shown in the Filament measurement screen.
    NECK("neck"),
    SHOULDERS("shoulders"),
    SHOULDER_LENGTH("shoulder_length"),
    CHEST("chest"),
    UNDERBUST("underbust"),
    BUST_HEIGHT("bust_height"),
    BUST_POINT_DISTANCE("bust_point_distance"),
    WAIST("waist"),
    ABDOMEN("abdomen"),
    HIPS("hips"),
    DRESS_LENGTH("dress_length"),
    ARM_LENGTH("arm_length"),
    UPPER_ARM("upper_arm"),
    WRIST("wrist"),

    // Legacy / advanced body channels retained for stored-profile compatibility and asymmetry.
    HAND("hand"),
    THIGH("thigh"),
    INSEAM("inseam"),
    CALF("calf"),
    FOOT("foot"),
}

/** Optional left/right values used by the Filament twin for real asymmetry. */
enum class BodySideMeasurement(val key: String) {
    LEFT_ARM_LENGTH("left_arm_length"),
    RIGHT_ARM_LENGTH("right_arm_length"),
    LEFT_UPPER_ARM("left_upper_arm"),
    RIGHT_UPPER_ARM("right_upper_arm"),
    LEFT_WRIST("left_wrist"),
    RIGHT_WRIST("right_wrist"),
    LEFT_HAND_LENGTH("left_hand_length"),
    RIGHT_HAND_LENGTH("right_hand_length"),
    LEFT_INSEAM("left_inseam"),
    RIGHT_INSEAM("right_inseam"),
    LEFT_FOOT_LENGTH("left_foot_length"),
    RIGHT_FOOT_LENGTH("right_foot_length"),
}

/** Minimum measurements needed to give a dress fit meaningful tailoring context. */
val essentialBodyMeasurements: List<BodyMeasurePoint> = listOf(
    BodyMeasurePoint.SHOULDERS,
    BodyMeasurePoint.CHEST,
    BodyMeasurePoint.UNDERBUST,
    BodyMeasurePoint.WAIST,
    BodyMeasurePoint.ABDOMEN,
    BodyMeasurePoint.HIPS,
    BodyMeasurePoint.DRESS_LENGTH,
    BodyMeasurePoint.ARM_LENGTH,
    BodyMeasurePoint.UPPER_ARM,
    BodyMeasurePoint.WRIST,
)

/**
 * Main tailoring flow. Height is tracked separately on BodyProfile, so this list plus full height
 * produces the 15 measurements presented by BodyMeasurementActivity.
 */
val guidedMeasurementOrder: List<BodyMeasurePoint> = listOf(
    BodyMeasurePoint.NECK,
    BodyMeasurePoint.SHOULDERS,
    BodyMeasurePoint.SHOULDER_LENGTH,
    BodyMeasurePoint.CHEST,
    BodyMeasurePoint.UNDERBUST,
    BodyMeasurePoint.BUST_HEIGHT,
    BodyMeasurePoint.BUST_POINT_DISTANCE,
    BodyMeasurePoint.WAIST,
    BodyMeasurePoint.ABDOMEN,
    BodyMeasurePoint.HIPS,
    BodyMeasurePoint.DRESS_LENGTH,
    BodyMeasurePoint.ARM_LENGTH,
    BodyMeasurePoint.UPPER_ARM,
    BodyMeasurePoint.WRIST,
)

data class BodyProfile(
    val heightInches: Float = 68f,
    val weightPounds: Float = 165f,
    val hasExplicitHeight: Boolean = false,
    val hasExplicitWeight: Boolean = false,
    val measurementsInches: Map<BodyMeasurePoint, Float> = emptyMap(),
    val sideMeasurementsInches: Map<BodySideMeasurement, Float> = emptyMap(),
) {
    val heightCentimeters: Float get() = heightInches * INCH_TO_CM
    val weightKilograms: Float get() = weightPounds * POUND_TO_KG

    val completedMeasurements: Int get() = guidedMeasurementOrder.count(measurementsInches::containsKey)
    val completionFraction: Float get() =
        completedMeasurements.toFloat() / guidedMeasurementOrder.size.toFloat()
    val essentialCompletedMeasurements: Int get() = essentialBodyMeasurements.count(measurementsInches::containsKey)
    val essentialCompletionFraction: Float get() =
        essentialCompletedMeasurements.toFloat() / essentialBodyMeasurements.size.toFloat()
    val isFitReady: Boolean get() = essentialBodyMeasurements.all(measurementsInches::containsKey)
    val isComplete: Boolean get() = guidedMeasurementOrder.all(measurementsInches::containsKey)
    val nextRecommendedMeasurement: BodyMeasurePoint? get() =
        guidedMeasurementOrder.firstOrNull { it !in measurementsInches }
    val remainingEssentialMeasurements: List<BodyMeasurePoint> get() =
        essentialBodyMeasurements.filterNot(measurementsInches::containsKey)

    companion object {
        private const val INCH_TO_CM = 2.54f
        private const val POUND_TO_KG = 0.45359237f
    }
}

@Singleton
class BodyProfileStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _onboardingComplete = MutableStateFlow(preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false))
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    private val _journeyMode = MutableStateFlow(readJourneyMode())
    val journeyMode: StateFlow<JourneyMode?> = _journeyMode.asStateFlow()

    private val _profile = MutableStateFlow(readProfile())
    val profile: StateFlow<BodyProfile> = _profile.asStateFlow()

    private val _digitalTwinSnapshotUri = MutableStateFlow(preferences.getString(KEY_DIGITAL_TWIN_SNAPSHOT, null))
    val digitalTwinSnapshotUri: StateFlow<String?> = _digitalTwinSnapshotUri.asStateFlow()

    fun setJourneyMode(mode: JourneyMode) {
        preferences.edit().putString(KEY_JOURNEY_MODE, mode.name).apply()
        _journeyMode.value = mode
    }

    fun setHeightInches(value: Float) {
        if (!value.isFinite() || value !in MIN_HEIGHT_IN..MAX_HEIGHT_IN) return
        preferences.edit().putFloat(KEY_HEIGHT_IN, value).apply()
        _profile.value = _profile.value.copy(heightInches = value, hasExplicitHeight = true)
    }

    fun setHeightCentimeters(value: Float) {
        if (!value.isFinite()) return
        setHeightInches(value / INCH_TO_CM)
    }

    fun setWeightPounds(value: Float) {
        if (!value.isFinite() || value !in MIN_WEIGHT_LB..MAX_WEIGHT_LB) return
        preferences.edit().putFloat(KEY_WEIGHT_LB, value).apply()
        _profile.value = _profile.value.copy(weightPounds = value, hasExplicitWeight = true)
    }

    fun setWeightKilograms(value: Float) {
        if (!value.isFinite()) return
        setWeightPounds(value / POUND_TO_KG)
    }

    fun setMeasurement(point: BodyMeasurePoint, inches: Float) {
        if (!inches.isFinite() || inches !in MIN_MEASUREMENT_IN..MAX_MEASUREMENT_IN) return
        preferences.edit().putFloat(measurementKey(point), inches).apply()
        _profile.value = _profile.value.copy(
            measurementsInches = _profile.value.measurementsInches + (point to inches),
        )
    }

    fun setMeasurementCentimeters(point: BodyMeasurePoint, centimeters: Float) {
        if (!centimeters.isFinite()) return
        setMeasurement(point, centimeters / INCH_TO_CM)
    }

    fun clearMeasurement(point: BodyMeasurePoint) {
        preferences.edit().remove(measurementKey(point)).apply()
        _profile.value = _profile.value.copy(
            measurementsInches = _profile.value.measurementsInches - point,
        )
    }

    fun setSideMeasurement(point: BodySideMeasurement, inches: Float) {
        if (!inches.isFinite() || inches !in MIN_MEASUREMENT_IN..MAX_MEASUREMENT_IN) return
        preferences.edit().putFloat(sideMeasurementKey(point), inches).apply()
        _profile.value = _profile.value.copy(
            sideMeasurementsInches = _profile.value.sideMeasurementsInches + (point to inches),
        )
    }

    fun setSideMeasurementCentimeters(point: BodySideMeasurement, centimeters: Float) {
        if (!centimeters.isFinite()) return
        setSideMeasurement(point, centimeters / INCH_TO_CM)
    }

    fun clearSideMeasurement(point: BodySideMeasurement) {
        preferences.edit().remove(sideMeasurementKey(point)).apply()
        _profile.value = _profile.value.copy(
            sideMeasurementsInches = _profile.value.sideMeasurementsInches - point,
        )
    }

    fun setDigitalTwinSnapshotUri(uri: String) {
        if (uri.isBlank()) return
        preferences.edit().putString(KEY_DIGITAL_TWIN_SNAPSHOT, uri).apply()
        _digitalTwinSnapshotUri.value = uri
    }

    fun clearDigitalTwinSnapshot() {
        preferences.edit().remove(KEY_DIGITAL_TWIN_SNAPSHOT).apply()
        _digitalTwinSnapshotUri.value = null
    }

    fun completeOnboarding() {
        preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
        _onboardingComplete.value = true
    }

    fun currentPromptContext(): String? {
        if (_journeyMode.value != JourneyMode.AVATAR) return null
        val current = _profile.value
        val measurements = current.measurementsInches
            .filterKeys { it in guidedMeasurementOrder }
            .toList()
            .sortedBy { it.first.ordinal }
            .joinToString(", ") { (point, value) -> "${point.key}=${format(value)}in" }
        val sideMeasurements = current.sideMeasurementsInches
            .toList()
            .sortedBy { it.first.ordinal }
            .joinToString(", ") { (point, value) -> "${point.key}=${format(value)}in" }

        val enteredFacts = buildList {
            if (current.hasExplicitHeight) add("height=${format(current.heightInches)}in/${format(current.heightCentimeters)}cm")
            if (current.hasExplicitWeight) add("weight=${format(current.weightPounds)}lb/${format(current.weightKilograms)}kg")
            if (measurements.isNotBlank()) add("dressmaking measurements: $measurements")
            if (sideMeasurements.isNotBlank()) add("left/right measurements: $sideMeasurements")
        }

        return buildString {
            append("Preserve the user's entered body proportions when fitting the garment.")
            if (enteredFacts.isNotEmpty()) append(" User-entered sizing facts: ${enteredFacts.joinToString(", ")}.")
            else append(" The user has not entered sizing measurements yet.")
            append(" Measurement profile status=${if (current.isFitReady) "fit-ready" else "partial"}.")
            if (_digitalTwinSnapshotUri.value != null) {
                append(" The person reference image is a render of the user's digital twin; preserve that body silhouette and proportions exactly.")
            }
            append(" Do not infer missing measurements or alter identity, pose, or body proportions beyond fitting the garment naturally.")
        }
    }

    private fun readJourneyMode(): JourneyMode? =
        preferences.getString(KEY_JOURNEY_MODE, null)
            ?.let { stored -> runCatching { JourneyMode.valueOf(stored) }.getOrNull() }

    private fun readProfile(): BodyProfile {
        val measurements = buildMap {
            BodyMeasurePoint.entries.forEach { point ->
                if (preferences.contains(measurementKey(point))) {
                    put(point, preferences.getFloat(measurementKey(point), 0f))
                }
            }
        }
        val sideMeasurements = buildMap {
            BodySideMeasurement.entries.forEach { point ->
                if (preferences.contains(sideMeasurementKey(point))) {
                    put(point, preferences.getFloat(sideMeasurementKey(point), 0f))
                }
            }
        }
        return BodyProfile(
            heightInches = preferences.getFloat(KEY_HEIGHT_IN, 68f),
            weightPounds = preferences.getFloat(KEY_WEIGHT_LB, 165f),
            hasExplicitHeight = preferences.contains(KEY_HEIGHT_IN),
            hasExplicitWeight = preferences.contains(KEY_WEIGHT_LB),
            measurementsInches = measurements,
            sideMeasurementsInches = sideMeasurements,
        )
    }

    private fun measurementKey(point: BodyMeasurePoint): String = "measurement_${point.key}_in"
    private fun sideMeasurementKey(point: BodySideMeasurement): String = "side_measurement_${point.key}_in"

    private fun format(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString()
        else "%.1f".format(java.util.Locale.US, value)

    companion object {
        private const val PREFERENCES_NAME = "almi_body_profile"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete_v6"
        private const val KEY_JOURNEY_MODE = "journey_mode"
        private const val KEY_HEIGHT_IN = "height_inches"
        private const val KEY_WEIGHT_LB = "weight_pounds"
        private const val KEY_DIGITAL_TWIN_SNAPSHOT = "digital_twin_snapshot_uri_v7"

        private const val INCH_TO_CM = 2.54f
        private const val POUND_TO_KG = 0.45359237f
        private const val MIN_HEIGHT_IN = 36f
        private const val MAX_HEIGHT_IN = 96f
        private const val MIN_WEIGHT_LB = 45f
        private const val MAX_WEIGHT_LB = 700f
        private const val MIN_MEASUREMENT_IN = 1f
        private const val MAX_MEASUREMENT_IN = 120f
    }
}
