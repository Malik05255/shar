package com.malik.alshurti

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Plays state-specific AI motion clips while preserving one master dog identity and camera.
 *
 * Full-body movement is never faked with zoom/translation. If a required clip is missing,
 * the exact master frame is shown instead. This keeps quality honest: standing means a real
 * stand-up clip, walking means a real walk clip, and sitting means a real sit-down clip.
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
    val clipName = remember(officeScene.dogAction, phase) {
        when (officeScene.dogAction) {
            DogAction.SEATED_IDLE -> if (phase == CallPhase.LISTENING) "dog_listening_loop" else "dog_idle_loop"
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

    key(clipResId, officeScene.revision) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(Uri.parse("android.resource://${ctx.packageName}/$clipResId"))
                    setOnPreparedListener { player ->
                        runCatching {
                            val looping = officeScene.dogAction in setOf(
                                DogAction.SEATED_IDLE,
                                DogAction.TALK_SEATED,
                                DogAction.TALK_STANDING
                            )
                            player.isLooping = looping
                            player.setVolume(0f, 0f)
                            start()
                        }
                    }
                    setOnErrorListener { _, _, _ -> true }
                }
            },
            update = { view ->
                if (!view.isPlaying && officeScene.dogAction in setOf(
                        DogAction.SEATED_IDLE,
                        DogAction.TALK_SEATED,
                        DogAction.TALK_STANDING
                    )
                ) {
                    runCatching { view.start() }
                }
            }
        )
    }
}
