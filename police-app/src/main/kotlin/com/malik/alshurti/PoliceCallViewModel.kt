package com.malik.alshurti

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Conversation coordinator for the living office.
 *
 * The office exists independently of the observer. Opening the app never triggers a greeting and
 * never makes the dog stare at the camera. Silent listening runs while the dog works. Only actual
 * detected speech interrupts the foreground task; after the reply the dog returns to office work.
 */
class PoliceCallViewModel(application: Application) : AndroidViewModel(application), PoliceVoiceEngine.Listener {
    private val preferences = application.getSharedPreferences(PREFS_NAME, 0)
    private val brain: PoliceBrain = LocalPoliceBrain()
    private val voiceEngine = PoliceVoiceEngine(application.applicationContext, this)
    private val officeSoundscape = OfficeSoundscape(application.applicationContext)
    private val sceneDirector = CinematicSceneDirector()
    private val livingOffice = LivingOfficeWorld()
    private val infiniteOffice = InfiniteOfficeScenarioGenerator()
    private val worldScheduler = OfficeWorldScheduler()
    private val scenarioCouncil = DualScenarioCouncil(
        continuityPlanner = GeminiScenarioProvider(GeminiScenarioProvider.Role.CONTINUITY),
        realismPlanner = GeminiScenarioProvider(GeminiScenarioProvider.Role.REALISM)
    )

    private val _uiState = MutableStateFlow(PoliceUiState(mode = VoiceMode.ONLINE))
    val uiState: StateFlow<PoliceUiState> = _uiState.asStateFlow()

    private var microphonePermissionGranted = false
    private var ttsReady = false
    private var sessionStarted = false
    private var completedPoliceTurns = 0
    private var standingReplyActive = false
    private var conversationJob: Job? = null
    private var ambientLifeJob: Job? = null

    init {
        preferences.edit().putString(KEY_MODE, VoiceMode.ONLINE.name).apply()
        officeSoundscape.start()
        officeSoundscape.setConversationPhase(CallPhase.STARTING)
        voiceEngine.setMode(VoiceMode.ONLINE)
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        microphonePermissionGranted = granted
        if (!granted) {
            setPhase(CallPhase.ERROR)
            _uiState.update {
                it.copy(
                    mood = DogMood.SERIOUS,
                    viseme = MouthViseme.REST,
                    errorMessage = "microphone-permission-required"
                )
            }
            return
        }
        tryStartSession()
    }

    fun chooseMode(mode: VoiceMode) {
        if (mode != VoiceMode.ONLINE) return

        stopAmbientLife()
        conversationJob?.cancel()
        sceneDirector.reset()
        livingOffice.reset()
        infiniteOffice.reset()
        scenarioCouncil.reset()
        RuntimeOfficePlanBus.clear()
        SceneContextRegistry.reset()
        completedPoliceTurns = 0
        ttsReady = false
        sessionStarted = false
        standingReplyActive = false
        preferences.edit().putString(KEY_MODE, VoiceMode.ONLINE.name).apply()
        voiceEngine.setMode(VoiceMode.ONLINE)
        setPhase(CallPhase.STARTING)
        _uiState.update {
            it.copy(
                mode = VoiceMode.ONLINE,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                errorMessage = null,
                officeScene = OfficeSceneState(
                    attention = DogAttention.PAPER,
                    scenario = CinematicScenario.AMBIENT_WORK,
                    backgroundActivity = BackgroundActivity.PAPERWORK,
                    revision = it.officeScene.revision + 1
                )
            )
        }
    }

    fun retryListening() {
        if (!microphonePermissionGranted || !sessionStarted) return
        standingReplyActive = false
        setPhase(CallPhase.LISTENING)
        voiceEngine.startListening()
        if (ambientLifeJob?.isActive != true) startAmbientLife()
    }

    fun interruptAndListen() {
        voiceEngine.interruptSpeech()
        conversationJob?.cancel()
        conversationJob = null
        standingReplyActive = false
        returnDogToWorkThenListen()
    }

    private fun tryStartSession() {
        if (!microphonePermissionGranted || !ttsReady || sessionStarted) return
        sessionStarted = true

        // Deliberately no spoken greeting. The viewer enters an office that is already working.
        setPhase(CallPhase.LISTENING)
        _uiState.update {
            it.copy(
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                replyText = "",
                heardText = "",
                errorMessage = null,
                firstGreetingDone = true,
                officeScene = it.officeScene.copy(
                    attention = DogAttention.PAPER,
                    dogAction = DogAction.REVIEW_FILE,
                    scenario = CinematicScenario.AMBIENT_WORK,
                    backgroundActivity = BackgroundActivity.PAPERWORK,
                    revision = it.officeScene.revision + 1
                )
            )
        }
        startAmbientLife()
        voiceEngine.startListening()
    }

    /**
     * The office clock is local and deterministic. AI plans for the NEXT beat in parallel with the
     * current beat; the clock never waits for network inference. If AI is late, it is cancelled and
     * the infinite local compositor supplies the next beat immediately.
     */
    private fun startAmbientLife() {
        ambientLifeJob?.cancel()
        ambientLifeJob = viewModelScope.launch {
            var next = infiniteOffice.next(observerEngaged = false)
            while (isActive && sessionStarted) {
                if (_uiState.value.phase == CallPhase.LISTENING) {
                    applyAmbientPlan(next)
                    val visibleForMs = next.durationHintMs.coerceIn(6_000L, 15_500L)
                    val planning = async { scenarioCouncil.next(ambientPlanningContext()) }

                    // World time advances independently from planning latency.
                    delay(visibleForMs)
                    val planned = if (planning.isCompleted) {
                        runCatching { planning.await() }.getOrNull()
                    } else {
                        planning.cancel()
                        null
                    }
                    next = sanitizeAmbientPlan(planned)
                        ?: infiniteOffice.next(observerEngaged = false)
                } else {
                    delay(250L)
                }
            }
        }
    }

    private fun ambientPlanningContext(): SceneContext {
        val current = SceneContextRegistry.snapshot()
        return current.copy(
            explicitCue = ExplicitSceneCue.NONE,
            suppressApproach = true
        )
    }

    private fun sanitizeAmbientPlan(plan: RuntimeScenarioPlan?): RuntimeScenarioPlan? {
        if (plan == null) return null
        val commands = plan.commands.filterNot {
            it.actor == SceneActorId.POLICE_DOG &&
                it.clip in setOf("LookAtCamera", "Talk", "Listen")
        }
        return plan.copy(commands = commands).takeIf { commands.isNotEmpty() }
    }

    private fun stopAmbientLife() {
        ambientLifeJob?.cancel()
        ambientLifeJob = null
    }

    /**
     * Runtime 3D receives the full choreography first. DogAction mapping below is legacy-only so an
     * old installation can still display the cinematic benchmark while the production GLB pack is
     * not yet enabled.
     */
    private fun applyAmbientPlan(plan: RuntimeScenarioPlan) {
        val scheduled = scheduleForRuntime(plan, observerEngaged = false)
        RuntimeOfficePlanBus.publish(scheduled)

        val dogCommands = scheduled.commands.filter { it.actor == SceneActorId.POLICE_DOG }
        val clips = dogCommands.map { it.clip }.toSet()

        val action = when {
            "UsePhone" in clips -> DogAction.ANSWER_PHONE
            clips.any { it in setOf("ReviewFile", "ReachFile", "TurnPage", "WriteNote") } -> DogAction.REVIEW_FILE
            else -> DogAction.SEATED_IDLE
        }
        val attention = when {
            clips.any { it == "LookAtDoor" } -> DogAttention.DOOR
            clips.any { it == "LookAtMonitor" } -> DogAttention.MONITOR
            action == DogAction.ANSWER_PHONE -> DogAttention.PHONE
            else -> DogAttention.PAPER
        }

        val background = when {
            scheduled.commands.any {
                it.actor != SceneActorId.POLICE_DOG && it.clip in setOf("TalkToStaff", "ListenToStaff")
            } -> BackgroundActivity.DESK_CONVERSATION
            scheduled.commands.any {
                it.actor != SceneActorId.POLICE_DOG && it.channel == AnimationChannel.LOCOMOTION
            } -> BackgroundActivity.STAFF_WALK
            scheduled.commands.any { it.actor == SceneActorId.DOOR } -> BackgroundActivity.DOOR_TRAFFIC
            action == DogAction.REVIEW_FILE -> BackgroundActivity.PAPERWORK
            else -> BackgroundActivity.CALM_WORK
        }

        val cue = when {
            scheduled.sounds.any { it.sound == OfficeSoundId.PHONE_RING } -> OfficeCue.PHONE_RING
            scheduled.sounds.any { it.sound == OfficeSoundId.DOOR_OPEN } -> OfficeCue.DOOR_OPEN
            scheduled.sounds.any { it.sound == OfficeSoundId.DOOR_CLOSE } -> OfficeCue.DOOR_CLOSE
            scheduled.sounds.any { it.sound == OfficeSoundId.FOOTSTEPS_SOFT } -> OfficeCue.FOOTSTEPS
            scheduled.sounds.any { it.sound in setOf(OfficeSoundId.PAGE_TURN, OfficeSoundId.PAPER_HANDLE) } -> OfficeCue.PAPER_RUSTLE
            else -> OfficeCue.NONE
        }

        _uiState.update {
            it.copy(
                mood = DogMood.CALM,
                officeScene = it.officeScene.copy(
                    cue = cue,
                    attention = attention,
                    dogAction = action,
                    scenario = CinematicScenario.AMBIENT_WORK,
                    backgroundActivity = background,
                    phoneRinging = cue == OfficeCue.PHONE_RING,
                    doorOpen = cue == OfficeCue.DOOR_OPEN,
                    staffVisible = true,
                    revision = it.officeScene.revision + 1
                )
            )
        }
        playPhysicalSounds(scheduled)
    }

    private fun scheduleForRuntime(
        plan: RuntimeScenarioPlan,
        observerEngaged: Boolean
    ): RuntimeScenarioPlan = worldScheduler.schedule(
        plan = plan,
        snapshot = OfficeWorldScheduler.Snapshot(
            actors = Runtime3DAssetCatalog.actors.associate { asset ->
                asset.id to OfficeWorldScheduler.ActorRuntimeState(
                    zone = asset.defaultZone,
                    standing = when (asset.id) {
                        SceneActorId.POLICE_DOG -> standingReplyActive
                        SceneActorId.VISITOR_01 -> true
                        SceneActorId.STAFF_MALE_01,
                        SceneActorId.STAFF_MALE_02,
                        SceneActorId.STAFF_FEMALE_01 -> false
                        else -> true
                    },
                    locomoting = false,
                    currentClip = null
                )
            },
            observerEngaged = observerEngaged
        )
    )

    private fun publishObserverPlan(plan: RuntimeScenarioPlan) {
        RuntimeOfficePlanBus.publish(scheduleForRuntime(plan, observerEngaged = true))
    }

    private fun speakingPlan(standing: Boolean): RuntimeScenarioPlan = RuntimeScenarioPlan(
        durationHintMs = 30_000L,
        reason = if (standing) "observer-reply-standing" else "observer-reply-seated",
        keepWorldRunning = true,
        commands = listOf(
            SceneAnimationCommand(
                SceneActorId.POLICE_DOG,
                if (standing) "Talk" else "Talk",
                AnimationChannel.FACE,
                loop = true,
                playbackRate = 1.0f
            ),
            SceneAnimationCommand(
                SceneActorId.POLICE_DOG,
                "LookAtCamera",
                AnimationChannel.GAZE,
                loop = true,
                blendMs = 120
            ),
            SceneAnimationCommand(
                SceneActorId.STAFF_FEMALE_01,
                "Type",
                AnimationChannel.HANDS,
                loop = true,
                playbackRate = 0.91f
            ),
            SceneAnimationCommand(
                SceneActorId.STAFF_MALE_02,
                "Read",
                AnimationChannel.HANDS,
                loop = true,
                playbackRate = 0.94f
            )
        )
    )

    private fun playPhysicalSounds(plan: RuntimeScenarioPlan) {
        // No ambience bed and no artificial hum. Only quiet sounds with a physical source are used.
        plan.sounds.forEach { sound ->
            when (sound.sound) {
                OfficeSoundId.PHONE_RING -> officeSoundscape.playCue(OfficeCue.PHONE_RING)
                OfficeSoundId.DOOR_OPEN -> officeSoundscape.playCue(OfficeCue.DOOR_OPEN)
                OfficeSoundId.DOOR_CLOSE -> officeSoundscape.playCue(OfficeCue.DOOR_CLOSE)
                OfficeSoundId.FOOTSTEPS_SOFT -> officeSoundscape.playCue(OfficeCue.FOOTSTEPS)
                OfficeSoundId.PAGE_TURN,
                OfficeSoundId.PAPER_HANDLE -> officeSoundscape.playCue(OfficeCue.PAPER_RUSTLE)
                else -> Unit
            }
        }
    }

    private fun handleRecognizedText(text: String) {
        if (text.isBlank()) {
            retryListening()
            return
        }

        stopAmbientLife()
        standingReplyActive = false
        setPhase(CallPhase.THINKING)
        _uiState.update {
            it.copy(
                heardText = text,
                mood = DogMood.THINKING,
                viseme = MouthViseme.REST,
                errorMessage = null,
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.NONE,
                    attention = DogAttention.CAMERA,
                    dogAction = DogAction.SEATED_IDLE,
                    phoneRinging = false,
                    scenario = CinematicScenario.NONE,
                    revision = it.officeScene.revision + 1
                )
            )
        }

        conversationJob?.cancel()
        conversationJob = viewModelScope.launch {
            try {
                val reply = brain.reply(text)
                val shouldStandForReply = sceneDirector.shouldStandForReply(
                    completedPoliceTurns = completedPoliceTurns,
                    mood = reply.mood
                )

                if (shouldStandForReply) {
                    publishObserverPlan(
                        RuntimeScenarioPlan(
                            durationHintMs = STAND_UP_MS,
                            reason = "stand-before-reply",
                            commands = listOf(
                                SceneAnimationCommand(SceneActorId.POLICE_DOG, "StandUp", AnimationChannel.BODY),
                                SceneAnimationCommand(SceneActorId.POLICE_DOG, "LookAtCamera", AnimationChannel.GAZE, loop = true)
                            )
                        )
                    )
                    _uiState.update {
                        it.copy(
                            replyText = reply.text,
                            mood = reply.mood,
                            officeScene = it.officeScene.copy(
                                attention = DogAttention.CAMERA,
                                dogAction = DogAction.STAND_UP,
                                scenario = CinematicScenario.STAND_AND_TALK,
                                revision = it.officeScene.revision + 1
                            )
                        )
                    }
                    delay(STAND_UP_MS)
                    standingReplyActive = true
                    _uiState.update {
                        it.copy(
                            officeScene = it.officeScene.copy(
                                attention = DogAttention.CAMERA,
                                dogAction = DogAction.TALK_STANDING,
                                scenario = CinematicScenario.STAND_AND_TALK,
                                revision = it.officeScene.revision + 1
                            )
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            replyText = reply.text,
                            mood = reply.mood,
                            officeScene = it.officeScene.copy(
                                attention = DogAttention.CAMERA,
                                dogAction = DogAction.TALK_SEATED,
                                scenario = CinematicScenario.NONE,
                                revision = it.officeScene.revision + 1
                            )
                        )
                    }
                }
                publishObserverPlan(speakingPlan(standingReplyActive))
                setPhase(CallPhase.SPEAKING)
                voiceEngine.speak(reply.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                standingReplyActive = false
                setPhase(CallPhase.ERROR)
                _uiState.update {
                    it.copy(
                        mood = DogMood.SERIOUS,
                        viseme = MouthViseme.REST,
                        errorMessage = error.message
                    )
                }
            }
        }
    }

    override fun onReadyToListen() {
        if (!sessionStarted) return
        setPhase(CallPhase.LISTENING)
        // Arming the microphone does not earn camera attention.
        if (ambientLifeJob?.isActive != true) startAmbientLife()
    }

    override fun onSpeechStarted() {
        if (!sessionStarted) return
        stopAmbientLife()
        standingReplyActive = false
        setPhase(CallPhase.LISTENING)
        publishObserverPlan(livingOffice.onObserverSpeechStarted())

        // First visible response to the observer happens only after real voice activity.
        _uiState.update {
            it.copy(
                mood = DogMood.LISTENING,
                viseme = MouthViseme.REST,
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.NONE,
                    attention = DogAttention.CAMERA,
                    dogAction = DogAction.SEATED_IDLE,
                    phoneRinging = false,
                    revision = it.officeScene.revision + 1
                )
            )
        }
    }

    override fun onPartialText(text: String) {
        _uiState.update { it.copy(heardText = text) }
    }

    override fun onFinalText(text: String) = handleRecognizedText(text)

    override fun onSpeechError(message: String, recoverable: Boolean) {
        if (recoverable && microphonePermissionGranted && sessionStarted) {
            // Silence/no-match is normal in an observational office.
            setPhase(CallPhase.LISTENING)
            if (ambientLifeJob?.isActive != true) startAmbientLife()
            viewModelScope.launch {
                delay(450L)
                voiceEngine.startListening()
            }
        } else {
            setPhase(CallPhase.ERROR)
            _uiState.update { it.copy(mood = DogMood.SERIOUS, errorMessage = message) }
        }
    }

    override fun onTtsPreparing(percent: Int, message: String) {
        setPhase(CallPhase.STARTING)
        _uiState.update { it.copy(mood = DogMood.CALM, viseme = MouthViseme.REST, errorMessage = null) }
    }

    override fun onTtsReady() {
        ttsReady = true
        tryStartSession()
    }

    override fun onTtsStarted() {
        setPhase(CallPhase.SPEAKING)
        publishObserverPlan(speakingPlan(standingReplyActive))
        _uiState.update {
            it.copy(
                mood = if (it.mood in setOf(DogMood.SMILE, DogMood.SERIOUS)) it.mood else DogMood.TALKING,
                officeScene = it.officeScene.copy(
                    attention = DogAttention.CAMERA,
                    dogAction = if (standingReplyActive) DogAction.TALK_STANDING else DogAction.TALK_SEATED,
                    scenario = if (standingReplyActive) CinematicScenario.STAND_AND_TALK else CinematicScenario.NONE,
                    revision = it.officeScene.revision + 1
                )
            )
        }
    }

    override fun onViseme(viseme: MouthViseme) {
        _uiState.update { it.copy(viseme = viseme) }
    }

    override fun onTtsFinished() {
        _uiState.update { it.copy(viseme = MouthViseme.REST) }
        if (!microphonePermissionGranted) return

        completedPoliceTurns += 1
        if (standingReplyActive) {
            standingReplyActive = false
            conversationJob?.cancel()
            conversationJob = viewModelScope.launch {
                setPhase(CallPhase.THINKING)
                publishObserverPlan(
                    RuntimeScenarioPlan(
                        durationHintMs = SIT_DOWN_MS,
                        reason = "sit-after-reply",
                        commands = listOf(
                            SceneAnimationCommand(SceneActorId.POLICE_DOG, "SitDown", AnimationChannel.BODY),
                            SceneAnimationCommand(SceneActorId.STAFF_FEMALE_01, "Type", AnimationChannel.HANDS, loop = true)
                        )
                    )
                )
                _uiState.update {
                    it.copy(
                        mood = DogMood.CALM,
                        officeScene = it.officeScene.copy(
                            attention = DogAttention.PAPER,
                            dogAction = DogAction.SIT_DOWN,
                            scenario = CinematicScenario.STAND_AND_TALK,
                            revision = it.officeScene.revision + 1
                        )
                    )
                }
                delay(SIT_DOWN_MS)
                returnDogToWorkThenListen()
            }
        } else {
            returnDogToWorkThenListen()
        }
    }

    override fun onTtsError(message: String) {
        standingReplyActive = false
        setPhase(CallPhase.ERROR)
        _uiState.update {
            it.copy(
                mood = DogMood.SERIOUS,
                viseme = MouthViseme.REST,
                errorMessage = message
            )
        }
    }

    private fun returnDogToWorkThenListen() {
        conversationJob?.cancel()
        conversationJob = viewModelScope.launch {
            setPhase(CallPhase.LISTENING)
            val returnPlan = livingOffice.returnToOfficeWork()
            applyAmbientPlan(returnPlan)
            delay(900L)
            voiceEngine.startListening()
            startAmbientLife()
        }
    }

    private fun setPhase(phase: CallPhase) {
        officeSoundscape.setConversationPhase(phase)
        _uiState.update { it.copy(phase = phase) }
    }

    override fun onCleared() {
        ambientLifeJob?.cancel()
        conversationJob?.cancel()
        RuntimeOfficePlanBus.clear()
        officeSoundscape.release()
        voiceEngine.release()
        super.onCleared()
    }

    private companion object {
        const val PREFS_NAME = "alshurti_voice_settings"
        const val KEY_MODE = "voice_mode"
        const val STAND_UP_MS = 5_100L
        const val SIT_DOWN_MS = 5_100L
    }
}
