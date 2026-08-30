package com.malik.alshurti

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Production visual stage.
 *
 * The character shown to users must be a licensed cinematic stylized-realistic 3D
 * police dog: feature-film animation quality, believable fur/anatomy/materials and
 * expressive facial rig. It is NOT a photographed real dog and NOT a flat mascot.
 *
 * We intentionally do not fall back to the old Canvas dog. Showing a cheap cartoon
 * when the product promises a high-end animated character is a product bug.
 *
 * Required body clips: Idle, Listen, Think, Smile, Laugh, Serious.
 * Required talking/viseme clips: TalkOpen, TalkWide, TalkRound, TalkClosed, TalkRest.
 */
@Composable
fun RealPoliceDogStage(
    mood: DogMood,
    phase: CallPhase,
    viseme: MouthViseme,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasRealModel = remember {
        runCatching {
            context.assets.list(MODEL_DIRECTORY)
                ?.any { it.equals(MODEL_FILE, ignoreCase = true) } == true
        }.getOrDefault(false)
    }

    if (!hasRealModel) {
        MissingProductionCharacter(modifier)
        return
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(
        modelLoader = modelLoader,
        fileLocation = MODEL_ASSET_PATH
    )

    SceneView(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = null,
        autoCenterContent = true,
        autoFitContent = true,
        framingPadding = 0.08f
    ) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                autoAnimate = false,
                animationName = animationFor(mood, phase, viseme),
                animationLoop = true,
                animationSpeed = animationSpeedFor(phase),
                scaleToUnits = 1.72f,
                centerOrigin = Position(x = 0f, y = -1f, z = 0f),
                position = Position(x = 0f, y = -0.18f, z = 0f)
            )
        }
    }
}

@Composable
private fun MissingProductionCharacter(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07111C)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "شخصية الشرطي السينمائية غير مثبتة في هذه النسخة",
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center
        )
    }
}

private fun animationFor(mood: DogMood, phase: CallPhase, viseme: MouthViseme): String = when {
    phase == CallPhase.SPEAKING -> when (viseme) {
        MouthViseme.OPEN -> "TalkOpen"
        MouthViseme.WIDE -> "TalkWide"
        MouthViseme.ROUND -> "TalkRound"
        MouthViseme.CLOSED -> "TalkClosed"
        MouthViseme.REST -> "TalkRest"
    }
    phase == CallPhase.LISTENING -> "Listen"
    phase == CallPhase.THINKING -> "Think"
    mood == DogMood.SMILE -> "Smile"
    mood == DogMood.SERIOUS || phase == CallPhase.ERROR -> "Serious"
    else -> "Idle"
}

private fun animationSpeedFor(phase: CallPhase): Float = when (phase) {
    CallPhase.SPEAKING -> 1.05f
    CallPhase.LISTENING -> 0.92f
    CallPhase.THINKING -> 0.82f
    else -> 0.75f
}

private const val MODEL_DIRECTORY = "models"
private const val MODEL_FILE = "police_dog.glb"
private const val MODEL_ASSET_PATH = "$MODEL_DIRECTORY/$MODEL_FILE"
