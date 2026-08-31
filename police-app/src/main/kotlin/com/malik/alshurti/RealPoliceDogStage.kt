package com.malik.alshurti

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * Preferred path is a persistent multi-actor runtime office delivered as a verified 3D content
 * pack. A finite MP4 deck is no longer accepted as a fallback because any finite clip set will
 * eventually repeat and break the illusion of a living office.
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
    val packState by Runtime3DContentPackManager.state.collectAsState()

    LaunchedEffect(Unit) {
        Runtime3DContentPackManager.ensureReady(context)
    }

    val runtimePack = (packState as? Runtime3DContentPackManager.State.Ready)?.pack
    if (runtimePack?.hasRequiredWorld() == true) {
        Runtime3DOfficeStage(pack = runtimePack, modifier = modifier)
        return
    }

    // Backward compatibility for a single bundled dog GLB while the full office pack is absent.
    val hasBundledDog = remember {
        runCatching {
            context.assets.list(MODEL_DIRECTORY)
                ?.any { it.equals(MODEL_FILE, ignoreCase = true) } == true
        }.getOrDefault(false)
    }
    if (hasBundledDog) {
        BundledPoliceDogStage(
            mood = mood,
            phase = phase,
            viseme = viseme,
            officeScene = officeScene,
            modifier = modifier
        )
        return
    }

    // Fail closed on motion quality. Keep one photoreal master frame until the verified runtime 3D
    // world is available instead of replaying migration MP4s. This guarantees there is no visual
    // loop or repeated cinematic scene in the fallback path.
    PhotorealPoliceDogFallback(
        phase = phase,
        attention = officeScene.attention,
        modifier = modifier
    )
}

@Composable
private fun BundledPoliceDogStage(
    mood: DogMood,
    phase: CallPhase,
    viseme: MouthViseme,
    officeScene: OfficeSceneState,
    modifier: Modifier
) {
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
                animationName = animationFor(mood, phase, viseme, officeScene),
                animationLoop = officeScene.dogAction in setOf(
                    DogAction.SEATED_IDLE,
                    DogAction.TALK_SEATED,
                    DogAction.TALK_STANDING
                ),
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
    scene: OfficeSceneState
): String = when (scene.dogAction) {
    DogAction.STAND_UP -> "StandUp"
    DogAction.TALK_STANDING -> when (viseme) {
        MouthViseme.OPEN -> "StandTalkOpen"
        MouthViseme.WIDE -> "StandTalkWide"
        MouthViseme.ROUND -> "StandTalkRound"
        MouthViseme.CLOSED -> "StandTalkClosed"
        MouthViseme.REST -> "StandTalkRest"
    }
    DogAction.WALK_AROUND_DESK -> "WalkAroundDesk"
    DogAction.APPROACH_CAMERA -> "ApproachCamera"
    DogAction.RETURN_FROM_CAMERA -> "ReturnFromCamera"
    DogAction.WALK_TO_PHONE -> "WalkToPhone"
    DogAction.ANSWER_PHONE -> "AnswerPhone"
    DogAction.WALK_TO_DOOR -> "WalkToDoor"
    DogAction.GREET_STAFF -> "GreetStaff"
    DogAction.RETURN_TO_DESK -> "ReturnToDesk"
    DogAction.REVIEW_FILE -> "ReviewFile"
    DogAction.SIT_DOWN -> "SitDown"
    DogAction.TALK_SEATED -> when (viseme) {
        MouthViseme.OPEN -> "TalkOpen"
        MouthViseme.WIDE -> "TalkWide"
        MouthViseme.ROUND -> "TalkRound"
        MouthViseme.CLOSED -> "TalkClosed"
        MouthViseme.REST -> "TalkRest"
    }
    DogAction.SEATED_IDLE -> when {
        phase == CallPhase.LISTENING -> "Listen"
        phase == CallPhase.THINKING -> "Think"
        scene.attention == DogAttention.DOOR || scene.attention == DogAttention.STAFF -> "Listen"
        scene.attention == DogAttention.PHONE -> "Think"
        mood == DogMood.SMILE -> "Smile"
        mood == DogMood.SERIOUS || phase == CallPhase.ERROR -> "Serious"
        else -> "Idle"
    }
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
