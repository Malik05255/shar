package com.malik.alshurti

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
 * Plays real state-specific AI motion clips while preserving one master dog identity/camera.
 *
 * Full-body motion is never faked with bitmap zoom/translation. If a requested clip is missing,
 * the exact cinematic master frame is shown instead.
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

    // Office cues can be intentionally short. Keep their physical performance clip alive long
    // enough to finish instead of cutting back to the seated frame after the sound cue ends.
    LaunchedEffect(officeScene.revision) {
        val eventAction = when {
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

    val clipName = remember(requestedAction, phase) {
        when (requestedAction) {
            DogAction.SEATED_IDLE -> if (phase == CallPhase.LISTENING) "dog_idle_loop" else "dog_idle_loop"
            DogAction.TALK_SEATED -> "dog_talk_seated"
            DogAction.STAND_UP -> "dog_stand_up"
            DogAction.TALK_STANDING -> "dog_talk_standing"
            DogAction.WALK_AROUND_DESK -> "dog_walk_around_desk"
            DogAction.APPROACH_CAMERA -> "dog_approach_camera"
            DogAction.WALK_TO_PHONE -> "dog_walk_to_phone"
            DogAction.ANSWER_PHONE -> "dog_answer_phone"
            DogAction.WALK_TO_DOOR -> "dog_walk_to_door"
            DogAction.GREET_STAFF -> "dog_greet_staff"
            DogAction.RETURN_TO_DESK -> "dog_return_to_desk"
            DogAction.SIT_DOWN -> "dog_sit_down"
        }
    }

    val clipResId = remember(clipName) {
        context.resources.getIdentifier(clipName, "raw", context.packageName)
    }

    if (clipResId == 0) {
        PhotorealPoliceDogFallback(
            phase = phase,
            attention = officeScene.attention,
            modifier = modifier
        )
        return
    }

    val shouldLoop = requestedAction in setOf(
        DogAction.SEATED_IDLE,
        DogAction.TALK_SEATED,
        DogAction.TALK_STANDING
    )

    key(clipResId, requestedAction) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(Uri.parse("android.resource://${ctx.packageName}/$clipResId"))
                    setOnPreparedListener { player ->
                        runCatching {
                            player.isLooping = shouldLoop
                            // Dialogue and Foley are generated by the app. Motion clips are visual-only.
                            player.setVolume(0f, 0f)
                            start()
                        }
                    }
                    setOnErrorListener { _, _, _ -> true }
                }
            },
            update = { view ->
                if (shouldLoop && !view.isPlaying) {
                    runCatching { view.start() }
                }
            }
        )
    }
}
