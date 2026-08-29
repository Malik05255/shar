package com.almi.ai.ui.body

import com.almi.ai.data.preferences.BodyMeasurePoint
import com.almi.ai.data.preferences.BodyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyShapeSolverTest {

    @Test
    fun untouchedProfileKeepsNeutralModel() {
        val shape = BodyShapeSolver.solve(BodyProfile())

        assertEquals(1f, shape.heightScale, 0.001f)
        assertEquals(1f, shape.widthScale, 0.001f)
        assertEquals(1f, shape.depthScale, 0.001f)
        assertEquals(0f, shape.confidence, 0.001f)
        assertTrue(!shape.isPersonalized)
    }

    @Test
    fun explicitHeightChangesVerticalMeshScale() {
        val shape = BodyShapeSolver.solve(
            BodyProfile(
                heightInches = 74f,
                hasExplicitHeight = true,
            )
        )

        assertTrue(shape.heightScale > 1f)
        assertEquals(1, shape.enteredShapeFacts)
    }

    @Test
    fun largerEnteredTorsoMeasurementsIncreaseBodyVolume() {
        val shape = BodyShapeSolver.solve(
            BodyProfile(
                measurementsInches = mapOf(
                    BodyMeasurePoint.SHOULDERS to 21f,
                    BodyMeasurePoint.CHEST to 46f,
                    BodyMeasurePoint.WAIST to 40f,
                    BodyMeasurePoint.HIPS to 45f,
                )
            )
        )

        assertTrue(shape.widthScale > 1f)
        assertTrue(shape.depthScale > 1f)
        assertTrue(shape.headWidthCompensation < 1f)
        assertTrue(shape.headDepthCompensation < 1f)
        assertEquals(4, shape.enteredShapeFacts)
    }

    @Test
    fun extremeInputsStayInsideSafeDeformationEnvelope() {
        val shape = BodyShapeSolver.solve(
            BodyProfile(
                heightInches = 96f,
                weightPounds = 700f,
                hasExplicitHeight = true,
                hasExplicitWeight = true,
                measurementsInches = mapOf(
                    BodyMeasurePoint.SHOULDERS to 60f,
                    BodyMeasurePoint.CHEST to 100f,
                    BodyMeasurePoint.WAIST to 100f,
                    BodyMeasurePoint.HIPS to 100f,
                )
            )
        )

        assertTrue(shape.heightScale <= 1.24f)
        assertTrue(shape.widthScale <= 1.38f)
        assertTrue(shape.depthScale <= 1.44f)
        assertEquals(1f, shape.confidence, 0.001f)
    }
}
