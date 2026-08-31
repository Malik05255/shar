package com.malik.alshurti

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * Ambient office footage follows a per-screen-session deck. A full cinematic URL is never replayed
 * automatically during silent observation. Once an ambient clip is consumed it is skipped for the
 * rest of the session. This prevents the old idle <-> review-file recycle from looking like a loop.
 *
 * The finite MP4 deck is only a migration bridge. When it is exhausted we intentionally keep the
 * final frame instead of silently starting an already-seen scene again. Persistent independent 3D
 * actors remain the destination for unbounded office life without replay.
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
    var speakingVisualStage by remember { mutableIntStateOf(0) }

    // A coherent, unique ambient deck. Blocks are shuffled per app-screen session but paired
    // transitions (walk-to-door -> return, stand -> sit) keep their physical continuity.
    val ambientDeck = remember {
        val opening = listOf(DogAction.REVIEW_FILE, DogAction.SEATED_IDLE).shuffled()
        val blocks = listOf(
            listOf(DogAction.ANSWER_PHONE),
            listOf(DogAction.WALK_TO_DOOR, DogAction.RETURN_TO_DESK),
            listOf(DogAction.STAND_UP, DogAction.SIT_DOWN)
        ).shuffled().flatten()
        (opening + blocks).distinctBy { RemoteCinematicAssets.sourceFor(it) }
    }
    var ambientIndex by remember { mutableIntStateOf(0) }
    val usedAmbientSources = remember { linkedSetOf<String>() }

    LaunchedEffect(officeScene.revision, phase) {
        continuationAction = null
        if (phase != CallPhase.SPEAKING) speakingVisualStage = 0
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

    fun nextUnusedAmbient(): DogAction? {
        while (ambientIndex < ambientDeck.size) {
            val candidate = ambientDeck[ambientIndex++]
            val source = RemoteCinematicAssets.sourceFor(candidate) ?: continue
            if (usedAmbientSources.add(source)) return candidate
        }
        return null
    }

    val explicitEventAction = when {
        officeScene.phoneRinging || officeScene.cue == OfficeCue.PHONE_RING -> DogAction.ANSWER_PHONE
        officeScene.cue == OfficeCue.DOOR_OPEN -> DogAction.WALK_TO_DOOR
        officeScene.cue == OfficeCue.DOOR_CLOSE -> DogAction.RETURN_TO_DESK
        officeScene.cue == OfficeCue.STAFF_SPEAK || officeScene.staffSpeaking -> DogAction.GREET_STAFF
        officeScene.cue == OfficeCue.PAPER_RUSTLE -> DogAction.REVIEW_FILE
        else -> null
    }
    val unusedExplicitEventAction = explicitEventAction?.takeIf { action ->
        val source = RemoteCinematicAssets.sourceFor(action)
        source != null && source !in usedAmbientSources
    }

    var ambientAction by remember { mutableStateOf<DogAction?>(null) }
    LaunchedEffect(phase) {
        if (phase == CallPhase.LISTENING && ambientAction == null) {
            ambientAction = nextUnusedAmbient()
        }
        if (phase == CallPhase.SPEAKING) speakingVisualStage = 0
    }

    val baseAction: DogAction? = when {
        phase == CallPhase.SPEAKING && speakingVisualStage >= 2 -> null
        phase == CallPhase.SPEAKING && officeScene.dogAction == DogAction.TALK_STANDING -> DogAction.TALK_STANDING
        phase == CallPhase.SPEAKING -> DogAction.TALK_SEATED
        phase == CallPhase.THINKING -> DogAction.SEATED_IDLE
        phase == CallPhase.LISTENING && unusedExplicitEventAction != null -> unusedExplicitEventAction
        phase == CallPhase.LISTENING -> ambientAction
        officeScene.dogAction != DogAction.SEATED_IDLE -> officeScene.dogAction
        stickyAction != null -> stickyAction
        else -> DogAction.SEATED_IDLE
    }
    val requestedAction = continuationAction ?: baseAction

    val remoteSource = remember(requestedAction) {
        requestedAction?.let { RemoteCinematicAssets.sourceFor(it) }
    }
    if (requestedAction == null || remoteSource == null) {
        PhotorealPoliceDogFallback(
            phase = phase,
            attention = officeScene.attention,
            modifier = modifier
        )
        return
    }

    LaunchedEffect(requestedAction, remoteSource) {
        if (phase == CallPhase.LISTENING) usedAmbientSources.add(remoteSource)
        CinematicMediaCache.prefetch(
            context = context,
            urls = buildList {
                add(remoteSource)
                addAll(RemoteCinematicAssets.likelyNext(requestedAction).filterNot { it in usedAmbientSources })
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
        when (phase) {
            CallPhase.LISTENING -> {
                continuationAction = null
                ambientAction = nextUnusedAmbient()
            }
            CallPhase.SPEAKING -> {
                when (completed) {
                    DogAction.TALK_SEATED -> {
                        speakingVisualStage = 1
                        continuationAction = DogAction.TALK_STANDING
                    }
                    DogAction.TALK_STANDING -> {
                        speakingVisualStage = 2
                        continuationAction = null
                    }
                    DogAction.STAND_UP -> {
                        speakingVisualStage = 1
                        continuationAction = DogAction.TALK_STANDING
                    }
                    else -> {
                        speakingVisualStage = 2
                        continuationAction = null
                    }
                }
            }
            else -> {
                continuationAction = when (completed) {
                    DogAction.APPROACH_CAMERA -> DogAction.RETURN_FROM_CAMERA
                    DogAction.WALK_TO_DOOR,
                    DogAction.GREET_STAFF -> DogAction.RETURN_TO_DESK
                    DogAction.STAND_UP -> DogAction.SIT_DOWN
                    else -> null
                }
            }
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
