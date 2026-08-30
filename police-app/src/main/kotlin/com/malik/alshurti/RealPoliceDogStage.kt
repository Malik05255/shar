package com.malik.alshurti

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Production visual stage.
 *
 * A licensed PBR GLB is rendered by Filament/SceneView when present. If the GLB is not
 * bundled yet, the app uses a photoreal cinematic reference frame rather than dropping
 * back to the old illustrated dog. True jaw/eye/ear/body animation remains the GLB path.
 */
@Composable
fun RealPoliceDogStage(
    mood: DogMood,
    phase: CallPhase,
    viseme: MouthViseme,
    officeScene: OfficeSceneState = OfficeSceneState(),
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
        PhotorealPoliceDogFallback(
            phase = phase,
            attention = officeScene.attention,
            modifier = modifier
        )
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
                animationName = animationFor(mood, phase, viseme, officeScene.attention),
                animationLoop = true,
                animationSpeed = animationSpeedFor(phase, officeScene.attention),
                scaleToUnits = 1.72f,
                centerOrigin = Position(x = 0f, y = -1f, z = 0f),
                position = Position(x = 0f, y = -0.18f, z = 0f)
            )
        }
    }
}

private fun animationFor(
    mood: DogMood,
    phase: CallPhase,
    viseme: MouthViseme,
    attention: DogAttention
): String = when {
    phase == CallPhase.SPEAKING -> when (viseme) {
        MouthViseme.OPEN -> "TalkOpen"
        MouthViseme.WIDE -> "TalkWide"
        MouthViseme.ROUND -> "TalkRound"
        MouthViseme.CLOSED -> "TalkClosed"
        MouthViseme.REST -> "TalkRest"
    }
    phase == CallPhase.LISTENING -> "Listen"
    phase == CallPhase.THINKING -> "Think"
    attention == DogAttention.DOOR || attention == DogAttention.STAFF -> "Listen"
    attention == DogAttention.PHONE -> "Think"
    mood == DogMood.SMILE -> "Smile"
    mood == DogMood.SERIOUS || phase == CallPhase.ERROR -> "Serious"
    else -> "Idle"
}

private fun animationSpeedFor(phase: CallPhase, attention: DogAttention): Float = when {
    phase == CallPhase.SPEAKING -> 1.05f
    phase == CallPhase.LISTENING -> 0.92f
    phase == CallPhase.THINKING -> 0.82f
    attention != DogAttention.CAMERA -> 0.88f
    else -> 0.75f
}

private const val MODEL_DIRECTORY = "models"
private const val MODEL_FILE = "police_dog.glb"
private const val MODEL_ASSET_PATH = "$MODEL_DIRECTORY/$MODEL_FILE"
