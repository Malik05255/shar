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
 * The final character is a real PBR GLB rendered by Google Filament through SceneView.
 * The existing Canvas character is kept only as a build-safe placeholder until a
 * licensed `models/police_dog.glb` is present in app assets.
 *
 * Asset animation contract (preferred clip names):
 * Idle, Listen, Think, Talk, Smile, Laugh, Serious.
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
        // Never pretend this is the final realistic character. This fallback exists only
        // so developers can build/test voice and call flow before the licensed GLB lands.
        PoliceDogStage(mood, phase, viseme, modifier)
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
                animationName = animationFor(mood, phase),
                animationLoop = phase != CallPhase.ERROR,
                animationSpeed = animationSpeedFor(phase),
                scaleToUnits = 1.72f,
                centerOrigin = Position(x = 0f, y = -1f, z = 0f),
                position = Position(x = 0f, y = -0.18f, z = 0f)
            )
        }
    }
}

private fun animationFor(mood: DogMood, phase: CallPhase): String = when {
    phase == CallPhase.LISTENING -> "Listen"
    phase == CallPhase.THINKING -> "Think"
    phase == CallPhase.SPEAKING && mood == DogMood.SMILE -> "Smile"
    phase == CallPhase.SPEAKING && mood == DogMood.SERIOUS -> "Serious"
    phase == CallPhase.SPEAKING -> "Talk"
    mood == DogMood.SMILE -> "Smile"
    mood == DogMood.SERIOUS -> "Serious"
    else -> "Idle"
}

private fun animationSpeedFor(phase: CallPhase): Float = when (phase) {
    CallPhase.SPEAKING -> 1.0f
    CallPhase.LISTENING -> 0.92f
    CallPhase.THINKING -> 0.82f
    else -> 0.75f
}

private const val MODEL_DIRECTORY = "models"
private const val MODEL_FILE = "police_dog.glb"
private const val MODEL_ASSET_PATH = "$MODEL_DIRECTORY/$MODEL_FILE"
