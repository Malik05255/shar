package com.almi.ai.ui.body

import com.almi.ai.data.preferences.BodyMeasurePoint
import com.almi.ai.data.preferences.BodyProfile
import kotlin.math.pow

/**
 * Deterministic envelope solver for the tailoring twin.
 *
 * Global height is controlled only by the user's full-height measurement. Limb lengths are handled
 * by Filament morphs / rig bones so changing an arm never stretches the whole person. Torso
 * circumferences drive the overall envelope, while weight provides a deliberately visible fallback
 * only where measured circumferences are still unknown.
 */
data class DigitalTwinShape(
    val heightScale: Float,
    val widthScale: Float,
    val depthScale: Float,
    val headWidthCompensation: Float,
    val headDepthCompensation: Float,
    val confidence: Float,
    val enteredShapeFacts: Int,
) {
    val isPersonalized: Boolean get() = enteredShapeFacts > 0
}

object BodyShapeSolver {
    private const val BASE_HEIGHT_IN = 65f
    private const val BASE_WEIGHT_LB = 160f
    private const val BASE_SHOULDERS_IN = 16.5f
    private const val BASE_BUST_IN = 36f
    private const val BASE_UNDERBUST_IN = 32f
    private const val BASE_WAIST_IN = 29f
    private const val BASE_ABDOMEN_IN = 33f
    private const val BASE_HIPS_IN = 39f

    fun solve(profile: BodyProfile): DigitalTwinShape {
        val m = profile.measurementsInches

        // Full height owns global vertical scale. Arm / dress lengths must not make the entire body
        // taller because they are independent tailoring facts.
        val heightScale = if (profile.hasExplicitHeight) {
            ratio(profile.heightInches, BASE_HEIGHT_IN, 0.78f, 1.24f)
        } else 1f

        val shoulder = m[BodyMeasurePoint.SHOULDERS]
            ?.let { ratio(it, BASE_SHOULDERS_IN, 0.74f, 1.38f) }
        val bust = m[BodyMeasurePoint.CHEST]
            ?.let { ratio(it, BASE_BUST_IN, 0.72f, 1.48f) }
        val underbust = m[BodyMeasurePoint.UNDERBUST]
            ?.let { ratio(it, BASE_UNDERBUST_IN, 0.72f, 1.44f) }
        val waist = m[BodyMeasurePoint.WAIST]
            ?.let { ratio(it, BASE_WAIST_IN, 0.68f, 1.54f) }
        val abdomen = m[BodyMeasurePoint.ABDOMEN]
            ?.let { ratio(it, BASE_ABDOMEN_IN, 0.70f, 1.56f) }
        val hips = m[BodyMeasurePoint.HIPS]
            ?.let { ratio(it, BASE_HIPS_IN, 0.72f, 1.50f) }

        // Weight fallback is intentionally stronger than before. A 20–30 kg edit must be obvious on
        // the digital twin, but entered circumferences still override this generic mass estimate.
        val massScale = if (profile.hasExplicitWeight) {
            val baseline = BASE_WEIGHT_LB * heightScale.toDouble().pow(2.15).toFloat()
            ratio(profile.weightPounds, baseline, 0.62f, 1.72f)
        } else 1f
        val massWidthHint = 1f + (massScale - 1f) * 0.42f
        val massDepthHint = 1f + (massScale - 1f) * 0.58f

        val measuredWidth = weightedAverage(
            buildList {
                shoulder?.let { add(it to 0.23f) }
                bust?.let { add(it to 0.23f) }
                underbust?.let { add(it to 0.09f) }
                waist?.let { add(it to 0.10f) }
                abdomen?.let { add(it to 0.13f) }
                hips?.let { add(it to 0.22f) }
            },
        )
        val measuredDepth = weightedAverage(
            buildList {
                bust?.let { add(it to 0.24f) }
                underbust?.let { add(it to 0.16f) }
                waist?.let { add(it to 0.13f) }
                abdomen?.let { add(it to 0.27f) }
                hips?.let { add(it to 0.20f) }
            },
        )

        // Preserve the long-standing safety envelope used by the renderer and tests. The stronger
        // weight coefficients above make normal edits more visible without allowing pathological
        // input to over-stretch the mesh.
        val widthScale = blendAvailable(
            measured = measuredWidth,
            fallback = massWidthHint,
            hasFallbackFact = profile.hasExplicitWeight,
            measuredWeight = 0.90f,
        ).coerceIn(0.72f, 1.38f)

        val depthScale = blendAvailable(
            measured = measuredDepth,
            fallback = massDepthHint,
            hasFallbackFact = profile.hasExplicitWeight,
            measuredWeight = 0.86f,
        ).coerceIn(0.70f, 1.44f)

        val factCount = buildList {
            if (profile.hasExplicitHeight) add(Unit)
            if (profile.hasExplicitWeight) add(Unit)
            if (m[BodyMeasurePoint.SHOULDERS] != null) add(Unit)
            if (m[BodyMeasurePoint.SHOULDER_LENGTH] != null) add(Unit)
            if (m[BodyMeasurePoint.CHEST] != null) add(Unit)
            if (m[BodyMeasurePoint.UNDERBUST] != null) add(Unit)
            if (m[BodyMeasurePoint.BUST_HEIGHT] != null) add(Unit)
            if (m[BodyMeasurePoint.BUST_POINT_DISTANCE] != null) add(Unit)
            if (m[BodyMeasurePoint.WAIST] != null) add(Unit)
            if (m[BodyMeasurePoint.ABDOMEN] != null) add(Unit)
            if (m[BodyMeasurePoint.HIPS] != null) add(Unit)
            if (m[BodyMeasurePoint.ARM_LENGTH] != null) add(Unit)
            if (m[BodyMeasurePoint.UPPER_ARM] != null) add(Unit)
            if (m[BodyMeasurePoint.WRIST] != null) add(Unit)
        }.size

        val coreFactCount = buildList {
            if (profile.hasExplicitHeight) add(Unit)
            if (profile.hasExplicitWeight) add(Unit)
            if (m[BodyMeasurePoint.SHOULDERS] != null) add(Unit)
            if (m[BodyMeasurePoint.CHEST] != null) add(Unit)
            if (m[BodyMeasurePoint.WAIST] != null) add(Unit)
            if (m[BodyMeasurePoint.HIPS] != null) add(Unit)
        }.size

        return DigitalTwinShape(
            heightScale = heightScale,
            widthScale = widthScale,
            depthScale = depthScale,
            headWidthCompensation = safeInverse(widthScale),
            headDepthCompensation = safeInverse(depthScale),
            confidence = (coreFactCount / 6f).coerceIn(0f, 1f),
            enteredShapeFacts = factCount,
        )
    }

    private fun ratio(value: Float, baseline: Float, min: Float, max: Float): Float =
        (value / baseline).coerceIn(min, max)

    private fun weightedAverage(values: List<Pair<Float, Float>>): Float? {
        if (values.isEmpty()) return null
        val totalWeight = values.sumOf { it.second.toDouble() }.toFloat()
        if (totalWeight <= 0f) return null
        return values.sumOf { (value, weight) -> (value * weight).toDouble() }.toFloat() / totalWeight
    }

    private fun blendAvailable(
        measured: Float?,
        fallback: Float,
        hasFallbackFact: Boolean,
        measuredWeight: Float,
    ): Float = when {
        measured != null && hasFallbackFact -> measured * measuredWeight + fallback * (1f - measuredWeight)
        measured != null -> measured
        hasFallbackFact -> fallback
        else -> 1f
    }

    private fun safeInverse(value: Float): Float = if (value == 0f) 1f else 1f / value
}
