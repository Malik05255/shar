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
 * Transitional cinematic performance player.
 *
 * The exact full-quality cinematic files are delivered from CDN/cache rather than bundled into the
 * APK. Each clip stays one-shot and holds its final frame. Runtime 3D remains the long-term primary
 * path; this player preserves the established film-quality benchmark during migration.
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

    val remoteSource = remember(requestedAction) { RemoteCinematicAssets.sourceFor(requestedAction) }
    if (remoteSource == null) {
        PhotorealPoliceDogFallback(
            phase = phase,
            attention = officeScene.attention,
            modifier = modifier
        )
        return
    }

    LaunchedEffect(requestedAction, remoteSource) {
        CinematicMediaCache.prefetch(
            context = context,
            urls = buildList {
                add(remoteSource)
                addAll(RemoteCinematicAssets.likelyNext(requestedAction))
            }
        )
    }

    val playbackSource = remember(requestedAction, remoteSource) {
        CinematicMediaCache.localOrRemote(context, remoteSource)
    }
    val playbackSeed = remember(requestedAction, remoteSource) {
        System.nanoTime() xor (requestedAction.ordinal.toLong() shl 33) xor remoteSource.hashCode().toLong()
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
                        source = playbackSource,
                        randomizeStart = false,
                        seed = playbackSeed
                    )
                }
            },
            update = { view ->
                view.bind(
                    source = playbackSource,
                    randomizeStart = false,
                    seed = playbackSeed
                )
            }
        )
    }
}
