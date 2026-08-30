package com.malik.alshurti

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Highest-fidelity fallback path before the final rigged GLB is bundled.
 *
 * If state-specific AI motion clips are present in res/raw, the app plays them as the visual
 * character layer. Missing/corrupt clips never break the call: the photoreal master frame is
 * used immediately instead.
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
    val clipName = remember(phase, officeScene.attention) {
        when {
            officeScene.attention == DogAttention.PHONE -> "dog_phone_react"
            officeScene.attention == DogAttention.DOOR || officeScene.attention == DogAttention.STAFF -> "dog_door_react"
            phase == CallPhase.SPEAKING -> "dog_speaking_loop"
            phase == CallPhase.LISTENING -> "dog_listening_loop"
            else -> "dog_idle_loop"
        }
    }
    val clipResId = remember(clipName) {
        context.resources.getIdentifier(clipName, "raw", context.packageName)
    }

    if (clipResId == 0) {
        PhotorealPoliceDogFallback(
            phase = phase,
            attention = officeScene.attention,
            viseme = viseme,
            modifier = modifier
        )
        return
    }

    key(clipResId) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(Uri.parse("android.resource://${ctx.packageName}/$clipResId"))
                    setOnPreparedListener { player ->
                        runCatching {
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            start()
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        // Returning true prevents Android's default error dialog. The next
                        // composition can safely fall back if the resource becomes unusable.
                        true
                    }
                }
            },
            update = { view ->
                if (!view.isPlaying) runCatching { view.start() }
            }
        )
    }
}
