package com.malik.alshurti

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State-specific cinematic performance player.
 *
 * Large body actions never fake motion with bitmap transforms. Idle and talking states use
 * specially prepared seamless living cycles so blinking/breathing/ears/mouth do not disappear
 * after the first few seconds.
 */
@Composable
fun AiCinematicDogStage(
    mood: DogMood,
    phase: CallPhase,
    viseme: MouthViseme,
    officeScene: OfficeSceneState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stickyAction by remember { mutableStateOf<DogAction?>(null) }
    var stickyJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(officeScene.revision) {
        val eventAction = when {
            officeScene.cue == OfficeCue.PAPER_RUSTLE -> DogAction.REVIEW_FILE
            officeScene.phoneRinging || officeScene.cue == OfficeCue.PHONE_RING -> DogAction.ANSWER_PHONE
            officeScene.cue == OfficeCue.DOOR_OPEN -> DogAction.WALK_TO_DOOR
            officeScene.cue == OfficeCue.STAFF_SPEAK || officeScene.staffSpeaking -> DogAction.GREET_STAFF
            officeScene.cue == OfficeCue.DOOR_CLOSE -> DogAction.RETURN_TO_DESK
            else -> null
        }
        if (eventAction != null) {
            stickyJob?.cancel()
            stickyAction = eventAction
            val holdMs = when (eventAction) {
                DogAction.REVIEW_FILE -> 5_100L
                DogAction.ANSWER_PHONE -> 6_100L
                DogAction.WALK_TO_DOOR -> 6_100L
                DogAction.GREET_STAFF -> 4_800L
                DogAction.RETURN_TO_DESK -> 6_100L
                else -> 4_500L
            }
            stickyJob = scope.launch {
                delay(holdMs)
                if (stickyAction == eventAction) stickyAction = null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stickyJob?.cancel() }
    }

    val requestedAction = when {
        officeScene.dogAction != DogAction.SEATED_IDLE -> officeScene.dogAction
        stickyAction != null -> stickyAction!!
        officeScene.phoneRinging || officeScene.attention == DogAttention.PHONE -> DogAction.ANSWER_PHONE
        officeScene.staffSpeaking || officeScene.staffAtDoor -> DogAction.GREET_STAFF
        officeScene.doorOpen || officeScene.attention == DogAttention.DOOR -> DogAction.WALK_TO_DOOR
        phase == CallPhase.SPEAKING -> DogAction.TALK_SEATED
        else -> DogAction.SEATED_IDLE
    }

    val candidateClipNames = remember(requestedAction) {
        when (requestedAction) {
            DogAction.SEATED_IDLE -> listOf("dog_idle_living", "dog_idle_loop")
            DogAction.TALK_SEATED -> listOf("dog_talk_seated_living", "dog_talk_seated")
            DogAction.STAND_UP -> listOf("dog_stand_up")
            DogAction.TALK_STANDING -> listOf("dog_talk_standing_living", "dog_talk_standing")
            DogAction.WALK_AROUND_DESK -> listOf("dog_walk_around_desk")
            DogAction.APPROACH_CAMERA -> listOf("dog_approach_camera")
            DogAction.RETURN_FROM_CAMERA -> listOf("dog_return_from_camera")
            DogAction.WALK_TO_PHONE -> listOf("dog_walk_to_phone")
            DogAction.ANSWER_PHONE -> listOf("dog_answer_phone")
            DogAction.WALK_TO_DOOR -> listOf("dog_walk_to_door")
            DogAction.GREET_STAFF -> listOf("dog_greet_staff", "dog_walk_to_door")
            DogAction.RETURN_TO_DESK -> listOf("dog_return_to_desk")
            DogAction.REVIEW_FILE -> listOf("dog_review_file")
            DogAction.SIT_DOWN -> listOf("dog_sit_down")
        }
    }

    val resolvedClip = remember(candidateClipNames) {
        candidateClipNames.firstNotNullOfOrNull { name ->
            context.resources.getIdentifier(name, "raw", context.packageName)
                .takeIf { it != 0 }
                ?.let { name to it }
        }
    }

    if (resolvedClip == null) {
        PhotorealPoliceDogFallback(
            phase = phase,
            attention = officeScene.attention,
            modifier = modifier
        )
        return
    }

    val (clipName, clipResId) = resolvedClip
    val continuous = clipName.endsWith("_living")

    // Living clips are already long seamless cycles; begin at their continuity frame. One-shot
    // body actions also always start at frame zero so feet/chair/desk continuity is deterministic.
    val randomizeStart = false
    val playbackSeed = remember(requestedAction, clipResId) {
        System.nanoTime() xor (requestedAction.ordinal.toLong() shl 33) xor clipResId.toLong()
    }

    Box(modifier = modifier.fillMaxSize()) {
        PhotorealPoliceDogFallback(
            phase = phase,
            attention = officeScene.attention,
            modifier = Modifier.fillMaxSize()
        )

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                CinematicClipView(ctx).apply {
                    bind(
                        resId = clipResId,
                        randomizeStart = randomizeStart,
                        seed = playbackSeed,
                        continuous = continuous
                    )
                }
            },
            update = { view ->
                view.bind(
                    resId = clipResId,
                    randomizeStart = randomizeStart,
                    seed = playbackSeed,
                    continuous = continuous
                )
            }
        )
    }
}
