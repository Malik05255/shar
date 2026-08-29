package com.almi.ai.ui.body

import android.content.Context
import android.content.Intent
import com.almi.ai.data.preferences.BodyMeasurePoint
import com.almi.ai.data.preferences.BodyProfile
import com.almi.ai.data.preferences.BodySideMeasurement

/** Primitive-only contract between ALMI and the native Filament measurement Activity. */
object BodyMeasurementContract {
    private const val EXTRA_LANGUAGE = "almi.body.language"
    private const val EXTRA_HEIGHT = "almi.body.height_in"
    private const val EXTRA_WEIGHT = "almi.body.weight_lb"
    private const val EXTRA_HAS_HEIGHT = "almi.body.has_height"
    private const val EXTRA_HAS_WEIGHT = "almi.body.has_weight"
    private const val MEASUREMENT_PREFIX = "almi.body.measurement."
    private const val SIDE_MEASUREMENT_PREFIX = "almi.body.side_measurement."

    fun createIntent(context: Context, language: String, profile: BodyProfile): Intent =
        Intent(context, TailorProMeasurementActivity::class.java).apply {
            putExtra(EXTRA_LANGUAGE, language)
            writeProfile(this, profile)
        }

    fun resultIntent(profile: BodyProfile): Intent = Intent().also { writeProfile(it, profile) }

    fun language(intent: Intent?): String =
        intent?.getStringExtra(EXTRA_LANGUAGE)?.takeIf { it == "ar" || it == "en" } ?: "ar"

    fun readProfile(intent: Intent?): BodyProfile {
        if (intent == null) return BodyProfile()
        val measurements = buildMap {
            BodyMeasurePoint.entries.forEach { point ->
                val key = measurementKey(point)
                if (intent.hasExtra(key)) {
                    val value = intent.getFloatExtra(key, Float.NaN)
                    if (value.isFinite() && value > 0f) put(point, value)
                }
            }
        }
        val sideMeasurements = buildMap {
            BodySideMeasurement.entries.forEach { point ->
                val key = sideMeasurementKey(point)
                if (intent.hasExtra(key)) {
                    val value = intent.getFloatExtra(key, Float.NaN)
                    if (value.isFinite() && value > 0f) put(point, value)
                }
            }
        }
        return BodyProfile(
            heightInches = intent.getFloatExtra(EXTRA_HEIGHT, 68f),
            weightPounds = intent.getFloatExtra(EXTRA_WEIGHT, 165f),
            hasExplicitHeight = intent.getBooleanExtra(EXTRA_HAS_HEIGHT, false),
            hasExplicitWeight = intent.getBooleanExtra(EXTRA_HAS_WEIGHT, false),
            measurementsInches = measurements,
            sideMeasurementsInches = sideMeasurements,
        )
    }

    private fun writeProfile(intent: Intent, profile: BodyProfile) {
        intent.putExtra(EXTRA_HEIGHT, profile.heightInches)
        intent.putExtra(EXTRA_WEIGHT, profile.weightPounds)
        intent.putExtra(EXTRA_HAS_HEIGHT, profile.hasExplicitHeight)
        intent.putExtra(EXTRA_HAS_WEIGHT, profile.hasExplicitWeight)
        profile.measurementsInches.forEach { (point, value) -> intent.putExtra(measurementKey(point), value) }
        profile.sideMeasurementsInches.forEach { (point, value) -> intent.putExtra(sideMeasurementKey(point), value) }
    }

    private fun measurementKey(point: BodyMeasurePoint): String = MEASUREMENT_PREFIX + point.name
    private fun sideMeasurementKey(point: BodySideMeasurement): String = SIDE_MEASUREMENT_PREFIX + point.name
}
