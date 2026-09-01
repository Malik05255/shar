package com.malik.alshurti

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.util.ArrayDeque
import kotlin.random.Random

/**
 * Continuous cinematic fallback director.
 *
 * This path is used only while a validated animated 3D pack is unavailable. Unlike the old finite
 * deck, motion never "runs out". Clips may be reused after a cooldown, never as an immediate repeat,
 * and every replay receives a new randomized start seed. The photoreal master frame remains below
 * the video at all times, so a CDN/player failure degrades gracefully instead of freezing black.
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
    val recentAmbient = remember { ArrayDeque<DogAction>() }
    val random = remember { Random(System.nanoTime()) }
    var playbackNonce by remember { mutableLongStateOf(1L) }
    var currentAction by remember { mutableStateOf<DogAction?>(null) }
    var forcedContinuation by remember { mutableStateOf<DogAction?>(null) }

    fun rememberAmbient(action: DogAction) {
        recentAmbient.remove(action)
        recentAmbient.addLast(action)
        while (recentAmbient.size > AMBIENT_COOLDOWN) recentAmbient.removeFirst()
    }

    fun nextAmbient(excluding: DogAction? = null): DogAction {
        val available = AMBIENT_POOL.filter { candidate ->
            candidate != excluding &&
                candidate !in recentAmbient &&
                RemoteCinematicAssets.sourceFor(candidate) != null
        }.ifEmpty {
            AMBIENT_POOL.filter { candidate ->
                candidate != excluding && RemoteCinematicAssets.sourceFor(candidate) != null
            }
        }.ifEmpty { listOf(DogAction.SEATED_IDLE) }

        val chosen = available[random.nextInt(available.size)]
        rememberAmbient(chosen)
        return chosen
    }

    val explicitEvent = when {
        officeScene.phoneRinging || officeScene.cue == OfficeCue.PHONE_RING -> DogAction.ANSWER_PHONE
        officeScene.cue == OfficeCue.DOOR_OPEN -> DogAction.WALK_TO_DOOR
        officeScene.cue == OfficeCue.DOOR_CLOSE -> DogAction.RETURN_TO_DESK
        officeScene.cue == OfficeCue.STAFF_SPEAK || officeScene.staffSpeaking -> DogAction.GREET_STAFF
        officeScene.cue == OfficeCue.PAPER_RUSTLE -> DogAction.REVIEW_FILE
        officeScene.dogAction == DogAction.STAND_UP -> DogAction.STAND_UP
        officeScene.dogAction == DogAction.SIT_DOWN -> DogAction.SIT_DOWN
        else -> null
    }

    LaunchedEffect(phase, officeScene.revision, explicitEvent) {
        forcedContinuation = null
        currentAction = when (phase) {
            CallPhase.SPEAKING -> if (officeScene.dogAction == DogAction.TALK_STANDING) {
                DogAction.TALK_STANDING
            } else {
                DogAction.TALK_SEATED
            }
            CallPhase.THINKING -> DogAction.REVIEW_FILE
            CallPhase.LISTENING -> explicitEvent ?: currentAction ?: nextAmbient()
            CallPhase.STARTING -> DogAction.SEATED_IDLE
            CallPhase.ERROR -> DogAction.SEATED_IDLE
        }
        playbackNonce += 1L
    }

    val requestedAction = forcedContinuation ?: currentAction ?: DogAction.SEATED_IDLE
    val remoteSource = RemoteCinematicAssets.sourceFor(requestedAction)

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
                if (phase == CallPhase.LISTENING) {
                    AMBIENT_POOL.mapNotNull(RemoteCinematicAssets::sourceFor)
                        .filterNot { it == remoteSource }
                        .take(3)
                        .let(::addAll)
                }
            }.distinct()
        )
    }

    val playbackSource = remember(remoteSource, playbackNonce) {
        CinematicMediaCache.localOrRemote(context, remoteSource)
    }
    val playbackSeed = remember(requestedAction, playbackSource, playbackNonce, officeScene.revision) {
        System.nanoTime() xor
            (requestedAction.ordinal.toLong() shl 31) xor
            playbackSource.hashCode().toLong() xor
            playbackNonce xor
            officeScene.revision.toLong()
    }
    val randomizeStart = requestedAction in setOf(
        DogAction.TALK_SEATED,
        DogAction.TALK_STANDING,
        DogAction.SEATED_IDLE,
        DogAction.REVIEW_FILE
    )

    fun advance(completed: DogAction) {
        playbackNonce += 1L
        when (phase) {
            CallPhase.SPEAKING -> {
                // Never exhaust speaking motion. Keep the same physical posture until TTS ends,
                // but restart at a different point in the take so long replies remain alive.
                forcedContinuation = if (officeScene.dogAction == DogAction.TALK_STANDING) {
                    DogAction.TALK_STANDING
                } else {
                    DogAction.TALK_SEATED
                }
            }
            CallPhase.THINKING -> {
                forcedContinuation = if (completed == DogAction.REVIEW_FILE) {
                    DogAction.SEATED_IDLE
                } else {
                    DogAction.REVIEW_FILE
                }
            }
            CallPhase.LISTENING -> {
                forcedContinuation = when (completed) {
                    DogAction.WALK_TO_DOOR,
                    DogAction.GREET_STAFF -> DogAction.RETURN_TO_DESK
                    DogAction.STAND_UP -> DogAction.SIT_DOWN
                    else -> nextAmbient(excluding = completed)
                }
            }
            CallPhase.STARTING,
            CallPhase.ERROR -> forcedContinuation = DogAction.SEATED_IDLE
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
                        onCompletion = { advance(requestedAction) }
                    )
                }
            },
            update = { view ->
                view.bind(
                    source = playbackSource,
                    randomizeStart = randomizeStart,
                    seed = playbackSeed,
                    onCompletion = { advance(requestedAction) }
                )
            }
        )
    }
}

private val AMBIENT_POOL = listOf(
    DogAction.SEATED_IDLE,
    DogAction.REVIEW_FILE,
    DogAction.ANSWER_PHONE,
    DogAction.WALK_TO_DOOR,
    DogAction.RETURN_TO_DESK
)

private const val AMBIENT_COOLDOWN = 2
