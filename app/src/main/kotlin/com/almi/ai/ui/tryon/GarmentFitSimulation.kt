package com.almi.ai.ui.tryon

import com.almi.ai.data.preferences.BodyMeasurePoint
import com.almi.ai.data.preferences.BodyProfile
import java.util.Locale

/** Apparel size selected by the user from the product page. */
enum class GarmentSize(val label: String) {
    XXS("XXS"),
    XS("XS"),
    S("S"),
    M("M"),
    L("L"),
    XL("XL"),
    XXL("XXL"),
    XXXL("3XL"),
}

enum class FitConfidence { LOW, MEDIUM, HIGH }
enum class FitPressure { UNKNOWN, VERY_TIGHT, TIGHT, CLOSE, REGULAR, LOOSE }

data class GarmentSizeMeasurements(
    /** Circumferences/lengths in inches. Null means the retailer did not expose the value. */
    val chest: Float? = null,
    val waist: Float? = null,
    val hips: Float? = null,
    val shoulderWidth: Float? = null,
    val sleeveLength: Float? = null,
    val inseam: Float? = null,
    val source: String = "",
)

data class FitSimulation(
    val size: GarmentSize,
    val confidence: FitConfidence,
    val overallPressure: FitPressure,
    val regionPressure: Map<BodyMeasurePoint, FitPressure>,
    val promptContext: String,
)

/**
 * Deterministic fit pre-pass before image generation.
 *
 * If a retailer size chart is available we compare it with the user's entered measurements. If the
 * chart is missing, the result is explicitly LOW confidence and the generator is told not to fake
 * numerical precision. Letter sizes alone are not treated as universal measurements.
 */
object GarmentFitSimulationEngine {
    fun simulate(
        profile: BodyProfile,
        size: GarmentSize,
        garmentMeasurements: GarmentSizeMeasurements? = null,
        productTitle: String = "",
        brand: String = "",
    ): FitSimulation {
        val comparisons = buildMap {
            compareCircumference(
                profile.measurementsInches[BodyMeasurePoint.CHEST],
                garmentMeasurements?.chest,
            )?.let { put(BodyMeasurePoint.CHEST, it) }
            compareCircumference(
                profile.measurementsInches[BodyMeasurePoint.WAIST],
                garmentMeasurements?.waist,
            )?.let { put(BodyMeasurePoint.WAIST, it) }
            compareCircumference(
                profile.measurementsInches[BodyMeasurePoint.HIPS],
                garmentMeasurements?.hips,
            )?.let { put(BodyMeasurePoint.HIPS, it) }
            compareLinear(
                profile.measurementsInches[BodyMeasurePoint.SHOULDERS],
                garmentMeasurements?.shoulderWidth,
            )?.let { put(BodyMeasurePoint.SHOULDERS, it) }
            compareLinear(
                profile.measurementsInches[BodyMeasurePoint.ARM_LENGTH],
                garmentMeasurements?.sleeveLength,
            )?.let { put(BodyMeasurePoint.ARM_LENGTH, it) }
            compareLinear(
                profile.measurementsInches[BodyMeasurePoint.INSEAM],
                garmentMeasurements?.inseam,
            )?.let { put(BodyMeasurePoint.INSEAM, it) }
        }

        val confidence = when {
            comparisons.size >= 4 -> FitConfidence.HIGH
            comparisons.size >= 2 -> FitConfidence.MEDIUM
            else -> FitConfidence.LOW
        }
        val overall = if (comparisons.isEmpty()) FitPressure.UNKNOWN else worstPressure(comparisons.values)

        val facts = buildList {
            if (profile.hasExplicitHeight) add("height=${fmt(profile.heightInches)}in")
            if (profile.hasExplicitWeight) {
                val kg = profile.weightPounds * 0.45359237f
                add("weight=${fmt(profile.weightPounds)}lb/${fmt(kg)}kg")
            }
            profile.measurementsInches.forEach { (point, value) -> add("${point.key}=${fmt(value)}in") }
        }
        val chartFacts = garmentMeasurements?.let { spec ->
            buildList {
                spec.chest?.let { add("garmentChest=${fmt(it)}in") }
                spec.waist?.let { add("garmentWaist=${fmt(it)}in") }
                spec.hips?.let { add("garmentHips=${fmt(it)}in") }
                spec.shoulderWidth?.let { add("garmentShoulders=${fmt(it)}in") }
                spec.sleeveLength?.let { add("garmentSleeve=${fmt(it)}in") }
                spec.inseam?.let { add("garmentInseam=${fmt(it)}in") }
            }
        }.orEmpty()

        val pressureFacts = comparisons.entries.joinToString(", ") { (point, pressure) ->
            "${point.key}=${pressure.name.lowercase(Locale.US)}"
        }

        val prompt = buildString {
            append("DIGITAL-TWIN FIT SIMULATION. ")
            append("Selected retailer size=${size.label}. ")
            if (productTitle.isNotBlank()) append("Product=$productTitle. ")
            if (brand.isNotBlank()) append("Brand=$brand. ")
            if (facts.isNotEmpty()) append("User-entered body facts: ${facts.joinToString(", ")}. ")
            if (chartFacts.isNotEmpty()) {
                append("Retailer size-chart facts for ${size.label}: ${chartFacts.joinToString(", ")}. ")
                append("Computed fit pressure: ${overall.name.lowercase(Locale.US)}")
                if (pressureFacts.isNotBlank()) append("; regions: $pressureFacts")
                append(". Render the garment with physically plausible tension, compression, looseness, hem position and sleeve length caused by this size/body mismatch. ")
            } else {
                append("No reliable retailer size-chart dimensions were extracted for ${size.label}. ")
                append("Do not pretend that the letter size has universal dimensions. Show only a cautious visual approximation and preserve the user's digital-twin proportions exactly. ")
            }
            append("Never slim, enlarge, beautify, or reshape the body merely to make the garment fit. The garment must adapt to the body, not the body to the garment.")
        }

        return FitSimulation(
            size = size,
            confidence = confidence,
            overallPressure = overall,
            regionPressure = comparisons,
            promptContext = prompt,
        )
    }

    private fun compareCircumference(body: Float?, garment: Float?): FitPressure? {
        if (body == null || garment == null || body <= 0f || garment <= 0f) return null
        // Positive ease = garment bigger than body. Negative ease = compression/tightness.
        val easeRatio = (garment - body) / body
        return when {
            easeRatio <= -0.12f -> FitPressure.VERY_TIGHT
            easeRatio <= -0.045f -> FitPressure.TIGHT
            easeRatio < 0.025f -> FitPressure.CLOSE
            easeRatio <= 0.12f -> FitPressure.REGULAR
            else -> FitPressure.LOOSE
        }
    }

    private fun compareLinear(body: Float?, garment: Float?): FitPressure? {
        if (body == null || garment == null || body <= 0f || garment <= 0f) return null
        val ratio = garment / body
        return when {
            ratio <= 0.88f -> FitPressure.VERY_TIGHT
            ratio <= 0.96f -> FitPressure.TIGHT
            ratio <= 1.03f -> FitPressure.CLOSE
            ratio <= 1.11f -> FitPressure.REGULAR
            else -> FitPressure.LOOSE
        }
    }

    private fun worstPressure(values: Collection<FitPressure>): FitPressure {
        val rank = mapOf(
            FitPressure.VERY_TIGHT to 5,
            FitPressure.TIGHT to 4,
            FitPressure.CLOSE to 3,
            FitPressure.REGULAR to 2,
            FitPressure.LOOSE to 1,
            FitPressure.UNKNOWN to 0,
        )
        return values.maxByOrNull { rank[it] ?: 0 } ?: FitPressure.UNKNOWN
    }

    private fun fmt(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(Locale.US, value)
}
