package com.malik.alshurti

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Cinematic office stage.
 *
 * Assets are intentionally split so every performer and prop can react independently:
 * office.glb       = room, desk, lights, phone, static props
 * police_dog.glb   = hero dog, full-body + facial rig
 * officer_a.glb    = background officer A
 * officer_b.glb    = background officer B
 * door.glb         = independent animated office door
 *
 * All five files must be authored/exported in the SAME world coordinate system and
 * unit scale. We keep their authored pivots instead of auto-scaling each file, which
 * preserves the exact composition created in Blender/Maya.
 */
@Composable
fun RealPoliceDogStage(
    mood: DogMood,
    phase: CallPhase,
    viseme: MouthViseme,
    officeScene: OfficeSceneState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val assets = remember {
        runCatching { context.assets.list(MODEL_DIRECTORY)?.toSet().orEmpty() }.getOrDefault(emptySet())
    }
    val hasFullScene = REQUIRED_MODELS.all { required ->
        assets.any { it.equals(required, ignoreCase = true) }
    }

    if (!hasFullScene) {
        CinematicEmptyStage(modifier)
        return
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val office = rememberModelInstance(modelLoader, "$MODEL_DIRECTORY/$OFFICE_FILE")
    val dog = rememberModelInstance(modelLoader, "$MODEL_DIRECTORY/$DOG_FILE")
    val officerA = rememberModelInstance(modelLoader, "$MODEL_DIRECTORY/$OFFICER_A_FILE")
    val officerB = rememberModelInstance(modelLoader, "$MODEL_DIRECTORY/$OFFICER_B_FILE")
    val door = rememberModelInstance(modelLoader, "$MODEL_DIRECTORY/$DOOR_FILE")

    SceneView(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = null,
        autoCenterContent = false,
        autoFitContent = false
    ) {
        office?.let { instance ->
            ModelNode(
                modelInstance = instance,
                autoAnimate = false,
                animationName = if (officeScene.phoneRinging) "PhoneRing" else "OfficeIdle",
                animationLoop = true,
                animationSpeed = 1f
            )
        }

        dog?.let { instance ->
            ModelNode(
                modelInstance = instance,
                autoAnimate = false,
                animationName = dogAnimation(mood, phase, viseme, officeScene.dogLookTarget),
                animationLoop = true,
                animationSpeed = dogAnimationSpeed(phase)
            )
        }

        officerA?.let { instance ->
            ModelNode(
                modelInstance = instance,
                autoAnimate = false,
                animationName = officerAnimation(officeScene.officerA),
                animationLoop = officeScene.officerA !in setOf(OfficeActorMotion.ENTER, OfficeActorMotion.EXIT),
                animationSpeed = officerAnimationSpeed(officeScene.officerA)
            )
        }

        officerB?.let { instance ->
            ModelNode(
                modelInstance = instance,
                autoAnimate = false,
                animationName = officerAnimation(officeScene.officerB),
                animationLoop = officeScene.officerB !in setOf(OfficeActorMotion.ENTER, OfficeActorMotion.EXIT),
                animationSpeed = officerAnimationSpeed(officeScene.officerB)
            )
        }

        door?.let { instance ->
            ModelNode(
                modelInstance = instance,
                autoAnimate = false,
                animationName = doorAnimation(officeScene.door),
                animationLoop = officeScene.door == OfficeDoorState.CLOSED || officeScene.door == OfficeDoorState.OPEN,
                animationSpeed = 1f
            )
        }
    }
}

@Composable
private fun CinematicEmptyStage(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0B1822),
                        Color(0xFF08131D),
                        Color(0xFF050C13)
                    )
                )
            )
    )
}

private fun dogAnimation(
    mood: DogMood,
    phase: CallPhase,
    viseme: MouthViseme,
    lookTarget: DogLookTarget
): String = when {
    lookTarget == DogLookTarget.DOOR -> "LookDoor"
    lookTarget == DogLookTarget.OFFICER_A -> "LookOfficerA"
    lookTarget == DogLookTarget.OFFICER_B -> "LookOfficerB"
    lookTarget == DogLookTarget.DESK -> "LookDesk"
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

private fun dogAnimationSpeed(phase: CallPhase): Float = when (phase) {
    CallPhase.SPEAKING -> 1.04f
    CallPhase.LISTENING -> 0.94f
    CallPhase.THINKING -> 0.84f
    else -> 0.78f
}

private fun officerAnimation(motion: OfficeActorMotion): String = when (motion) {
    OfficeActorMotion.IDLE -> "Idle"
    OfficeActorMotion.WALK_LEFT -> "WalkLeft"
    OfficeActorMotion.WALK_RIGHT -> "WalkRight"
    OfficeActorMotion.DESK_WORK -> "DeskWork"
    OfficeActorMotion.TURN_TO_DOOR -> "TurnDoor"
    OfficeActorMotion.ENTER -> "Enter"
    OfficeActorMotion.TALK -> "Talk"
    OfficeActorMotion.EXIT -> "Exit"
}

private fun officerAnimationSpeed(motion: OfficeActorMotion): Float = when (motion) {
    OfficeActorMotion.WALK_LEFT, OfficeActorMotion.WALK_RIGHT, OfficeActorMotion.ENTER, OfficeActorMotion.EXIT -> 1.0f
    OfficeActorMotion.TALK -> 0.94f
    OfficeActorMotion.DESK_WORK -> 0.86f
    else -> 0.8f
}

private fun doorAnimation(state: OfficeDoorState): String = when (state) {
    OfficeDoorState.CLOSED -> "Closed"
    OfficeDoorState.OPENING -> "Opening"
    OfficeDoorState.OPEN -> "Open"
    OfficeDoorState.CLOSING -> "Closing"
}

private const val MODEL_DIRECTORY = "models"
private const val OFFICE_FILE = "office.glb"
private const val DOG_FILE = "police_dog.glb"
private const val OFFICER_A_FILE = "officer_a.glb"
private const val OFFICER_B_FILE = "officer_b.glb"
private const val DOOR_FILE = "door.glb"
private val REQUIRED_MODELS = setOf(OFFICE_FILE, DOG_FILE, OFFICER_A_FILE, OFFICER_B_FILE, DOOR_FILE)
