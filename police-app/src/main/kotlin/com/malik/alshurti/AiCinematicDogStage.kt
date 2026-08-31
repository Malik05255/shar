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
 * Full-quality clips are delivered from CDN/cache. A clip may be one-shot, but the *office* is not:
 * completion advances into another context-compatible continuation so the final frame is never
 * exposed as a long freeze. Runtime 3D remains the destination for fully independent continuous
 * motion; this player preserves the existing cinematic benchmark during that migration.
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
    var continuationAction by remember { mutableStateOf<DogAction?>(null) }
    var continuationNonce by remember { mutableStateOf(0L) }

    LaunchedEffect(officeScene.revision, phase) {
        // A real state change always wins over a locally chained continuation.
        continuationAction = null
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
                DogAction.REVIEW_FILE -> 4_700L
                DogAction.ANSWER_PHONE -> 5_700L
                DogAction.WALK_TO_DOOR -> 5_700L
                DogAction.GREET_STAFF -> 4_500L
                DogAction.RETURN_TO_DESK -> 5_700L
                else -> 4_400L
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

    val baseAction = when {
        officeScene.dogAction != DogAction.SEATED_IDLE -> officeScene.dogAction
        stickyAction != null -> stickyAction!!
        officeScene.phoneRinging || officeScene.attention == DogAttention.PHONE -> DogAction.ANSWER_PHONE
        officeScene.staffSpeaking || officeScene.staffAtDoor -> DogAction.GREET_STAFF
        officeScene.doorOpen || officeScene.attention == DogAttention.DOOR -> DogAction.WALK_TO_DOOR
        phase == CallPhase.SPEAKING -> DogAction.TALK_SEATED
        else -> DogAction.SEATED_IDLE
    }
    val requestedAction = continuationAction ?: baseAction

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
    val playbackSeed = remember(requestedAction, remoteSource, continuationNonce) {
        System.nanoTime() xor
            (requestedAction.ordinal.toLong() shl 33) xor
            remoteSource.hashCode().toLong() xor
            continuationNonce
    }
    val randomizeStart = phase == CallPhase.SPEAKING && continuationNonce > 0L

    fun advanceFrom(completed: DogAction) {
        continuationNonce += 1L
        continuationAction = when {
            phase == CallPhase.SPEAKING && completed == DogAction.TALK_SEATED -> DogAction.TALK_SEATED
            phase == CallPhase.SPEAKING && completed == DogAction.TALK_STANDING -> DogAction.TALK_STANDING
            phase == CallPhase.LISTENING && completed == DogAction.REVIEW_FILE -> DogAction.SEATED_IDLE
            phase == CallPhase.LISTENING && completed == DogAction.SEATED_IDLE -> DogAction.REVIEW_FILE
            completed == DogAction.ANSWER_PHONE -> DogAction.SEATED_IDLE
            completed == DogAction.WALK_TO_DOOR -> DogAction.RETURN_TO_DESK
            completed == DogAction.GREET_STAFF -> DogAction.RETURN_TO_DESK
            completed == DogAction.RETURN_TO_DESK -> DogAction.SEATED_IDLE
            completed == DogAction.APPROACH_CAMERA -> DogAction.RETURN_FROM_CAMERA
            completed == DogAction.RETURN_FROM_CAMERA -> DogAction.SEATED_IDLE
            completed == DogAction.STAND_UP && phase == CallPhase.SPEAKING -> DogAction.TALK_STANDING
            completed == DogAction.SIT_DOWN -> DogAction.SEATED_IDLE
            else -> DogAction.SEATED_IDLE
        }
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
                        randomizeStart = randomizeStart,
                        seed = playbackSeed,
                        onCompletion = { advanceFrom(requestedAction) }
                    )
                }
            },
            update = { view ->
                view.bind(
                    source = playbackSource,
                    randomizeStart = randomizeStart,
                    seed = playbackSeed,
                    onCompletion = { advanceFrom(requestedAction) }
                )
            }
        )
    }
}
