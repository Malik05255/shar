package com.malik.alshurti

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Device-first conversation coordinator.
 *
 * Every app launch starts from the recommended backend instead of inheriting a stale mode from an
 * older APK. Recoverable audio failures are visible in UI briefly before retrying; they are never
 * silently swallowed.
 */
class PoliceCallViewModel(application: Application) : AndroidViewModel(application), PoliceVoiceEngine.Listener {
    private val onlineBrain: PoliceBrain = HybridPoliceBrain()
    private val offlineBrain: PoliceBrain = DeterministicPoliceBrain()
    private val voiceEngine = PoliceVoiceEngine(application.applicationContext, this)
    private val officeSoundscape = OfficeSoundscape(application.applicationContext)
    private val random = Random(System.nanoTime())

    private val initialMode: VoiceMode = voiceEngine.recommendedStartupMode()
    private val _uiState = MutableStateFlow(PoliceUiState(mode = initialMode))
    val uiState: StateFlow<PoliceUiState> = _uiState.asStateFlow()

    private var microphonePermissionGranted = false
    private var ttsReady = false
    private var sessionStarted = false
    private var openingGreetingInFlight = false
    private var conversationJob: Job? = null
    private var ambientJob: Job? = null
    private var ambientIndex = 0

    init {
        officeSoundscape.start()
        officeSoundscape.setConversationPhase(CallPhase.STARTING)
        voiceEngine.setMode(initialMode)
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        microphonePermissionGranted = granted
        if (!granted) {
            setPhase(CallPhase.ERROR)
            _uiState.update {
                it.copy(
                    mood = DogMood.SERIOUS,
                    errorMessage = "إذن الميكروفون مطلوب لتشغيل المحادثة."
                )
            }
            return
        }
        tryStartSession()
    }

    fun chooseMode(mode: VoiceMode) {
        if (_uiState.value.mode == mode && sessionStarted) return

        conversationJob?.cancel()
        stopAmbientLife()
        voiceEngine.stopListening()
        voiceEngine.interruptSpeech()
        RuntimeOfficePlanBus.clear()
        SceneContextRegistry.reset()

        ttsReady = false
        sessionStarted = false
        openingGreetingInFlight = false
        ambientIndex = 0

        _uiState.update {
            it.copy(
                mode = mode,
                phase = CallPhase.STARTING,
                mood = DogMood.CALM,
                viseme = MouthViseme.REST,
                heardText = "",
                replyText = "",
                errorMessage = null,
                firstGreetingDone = false,
                officeScene = OfficeSceneState(revision = it.officeScene.revision + 1)
            )
        }
        officeSoundscape.setConversationPhase(CallPhase.STARTING)
        voiceEngine.setMode(mode)
    }

    fun retryListening() {
        if (!microphonePermissionGranted) return
        if (!sessionStarted) {
            tryStartSession()
            return
        }
        recoverToListening(delayMs = 0L)
    }

    fun interruptAndListen() {
        if (!sessionStarted) return
        conversationJob?.cancel()
        conversationJob = null
        openingGreetingInFlight = false
        voiceEngine.interruptSpeech()
        recoverToListening(delayMs = 120L)
    }

    private fun tryStartSession() {
        if (!microphonePermissionGranted || !ttsReady || sessionStarted) return
        sessionStarted = true
        openingGreetingInFlight = true
        stopAmbientLife()
        setPhase(CallPhase.SPEAKING)
        setDogSpeaking(OPENING_GREETING, DogMood.SMILE)
        voiceEngine.speak(OPENING_GREETING)
    }

    private fun activeBrain(): PoliceBrain =
        if (_uiState.value.mode == VoiceMode.ONLINE) onlineBrain else offlineBrain

    private fun handleRecognizedText(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank()) {
            recoverToListening(delayMs = 180L)
            return
        }

        stopAmbientLife()
        setPhase(CallPhase.THINKING)
        _uiState.update {
            it.copy(
                heardText = text,
                mood = DogMood.THINKING,
                viseme = MouthViseme.REST,
                errorMessage = null,
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.PAPER_RUSTLE,
                    attention = DogAttention.PAPER,
                    dogAction = DogAction.REVIEW_FILE,
                    scenario = CinematicScenario.AMBIENT_WORK,
                    backgroundActivity = BackgroundActivity.PAPERWORK,
                    revision = it.officeScene.revision + 1
                )
            )
        }
        publishDogPlan("ReviewFile", AnimationChannel.HANDS, loop = true, reason = "thinking")

        conversationJob?.cancel()
        conversationJob = viewModelScope.launch {
            try {
                val reply = activeBrain().reply(text)
                setPhase(CallPhase.SPEAKING)
                setDogSpeaking(reply.text, reply.mood)
                voiceEngine.speak(reply.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val fallback = runCatching { offlineBrain.reply(text) }
                    .getOrElse { PoliceReply("أنا سامعك يا بطل. قل لي مرة ثانية وش صار؟", DogMood.CALM) }
                _uiState.update { it.copy(errorMessage = "تعذر رد Gemini؛ استخدمت الرد المحلي لهذه الجولة.") }
                setPhase(CallPhase.SPEAKING)
                setDogSpeaking(fallback.text, fallback.mood, keepError = true)
                voiceEngine.speak(fallback.text)
            }
        }
    }

    private fun setDogSpeaking(text: String, mood: DogMood, keepError: Boolean = false) {
        _uiState.update {
            it.copy(
                replyText = text,
                mood = mood,
                viseme = MouthViseme.REST,
                errorMessage = if (keepError) it.errorMessage else null,
                firstGreetingDone = true,
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.NONE,
                    attention = DogAttention.CAMERA,
                    dogAction = DogAction.TALK_SEATED,
                    scenario = CinematicScenario.NONE,
                    backgroundActivity = BackgroundActivity.CALM_WORK,
                    phoneRinging = false,
                    staffSpeaking = false,
                    revision = it.officeScene.revision + 1
                )
            )
        }
        publishDogPlan("Talk", AnimationChannel.FACE, loop = true, reason = "speaking")
    }

    private fun recoverToListening(delayMs: Long) {
        if (!microphonePermissionGranted || !sessionStarted) return
        conversationJob?.cancel()
        conversationJob = viewModelScope.launch {
            if (delayMs > 0L) delay(delayMs)
            setPhase(CallPhase.LISTENING)
            _uiState.update {
                it.copy(
                    mood = DogMood.CALM,
                    viseme = MouthViseme.REST,
                    errorMessage = null,
                    officeScene = it.officeScene.copy(
                        cue = OfficeCue.NONE,
                        attention = DogAttention.PAPER,
                        dogAction = DogAction.SEATED_IDLE,
                        scenario = CinematicScenario.AMBIENT_WORK,
                        backgroundActivity = BackgroundActivity.CALM_WORK,
                        phoneRinging = false,
                        staffSpeaking = false,
                        revision = it.officeScene.revision + 1
                    )
                )
            }
            publishDogPlan("IdleWork", AnimationChannel.BODY, loop = true, reason = "listening")
            voiceEngine.startListening()
            startAmbientLife()
        }
    }

    private fun startAmbientLife() {
        if (ambientJob?.isActive == true) return
        ambientJob = viewModelScope.launch {
            while (isActive && sessionStarted) {
                if (_uiState.value.phase != CallPhase.LISTENING) {
                    delay(250L)
                    continue
                }
                delay(random.nextLong(4_800L, 9_200L))
                if (_uiState.value.phase != CallPhase.LISTENING) continue
                applyNextAmbientBeat()
            }
        }
    }

    private fun stopAmbientLife() {
        ambientJob?.cancel()
        ambientJob = null
    }

    private fun applyNextAmbientBeat() {
        val beat = AMBIENT_BEATS[ambientIndex % AMBIENT_BEATS.size]
        ambientIndex += 1
        _uiState.update { state ->
            state.copy(
                officeScene = state.officeScene.copy(
                    cue = beat.cue,
                    attention = beat.attention,
                    dogAction = beat.action,
                    scenario = CinematicScenario.AMBIENT_WORK,
                    backgroundActivity = beat.background,
                    phoneRinging = beat.cue == OfficeCue.PHONE_RING,
                    staffSpeaking = beat.cue == OfficeCue.STAFF_SPEAK,
                    revision = state.officeScene.revision + 1
                )
            )
        }
        when (beat.cue) {
            OfficeCue.PHONE_RING -> officeSoundscape.playCue(OfficeCue.PHONE_RING)
            OfficeCue.DOOR_OPEN -> officeSoundscape.playCue(OfficeCue.DOOR_OPEN)
            OfficeCue.DOOR_CLOSE -> officeSoundscape.playCue(OfficeCue.DOOR_CLOSE)
            OfficeCue.FOOTSTEPS,
            OfficeCue.STAFF_PASS -> officeSoundscape.playCue(OfficeCue.FOOTSTEPS)
            OfficeCue.PAPER_RUSTLE -> officeSoundscape.playCue(OfficeCue.PAPER_RUSTLE)
            else -> Unit
        }
        val clip = when (beat.action) {
            DogAction.REVIEW_FILE -> "ReviewFile"
            DogAction.ANSWER_PHONE -> "UsePhone"
            DogAction.WALK_TO_DOOR -> "LookAtDoor"
            DogAction.RETURN_TO_DESK -> "IdleWork"
            else -> "IdleWork"
        }
        publishDogPlan(clip, AnimationChannel.BODY, loop = beat.action == DogAction.SEATED_IDLE, reason = "ambient")
    }

    private fun publishDogPlan(
        clip: String,
        channel: AnimationChannel,
        loop: Boolean,
        reason: String
    ) {
        RuntimeOfficePlanBus.publish(
            RuntimeScenarioPlan(
                commands = listOf(
                    SceneAnimationCommand(
                        actor = SceneActorId.POLICE_DOG,
                        clip = clip,
                        channel = channel,
                        loop = loop,
                        blendMs = 180
                    )
                ),
                durationHintMs = 8_000L,
                reason = reason,
                keepWorldRunning = true
            )
        )
    }

    override fun onReadyToListen() {
        if (!sessionStarted || openingGreetingInFlight) return
        setPhase(CallPhase.LISTENING)
        _uiState.update { it.copy(errorMessage = null) }
        if (ambientJob?.isActive != true) startAmbientLife()
    }

    override fun onSpeechStarted() {
        if (!sessionStarted || openingGreetingInFlight) return
        stopAmbientLife()
        setPhase(CallPhase.LISTENING)
        _uiState.update {
            it.copy(
                mood = DogMood.LISTENING,
                errorMessage = null,
                officeScene = it.officeScene.copy(
                    cue = OfficeCue.NONE,
                    attention = DogAttention.CAMERA,
                    dogAction = DogAction.SEATED_IDLE,
                    revision = it.officeScene.revision + 1
                )
            )
        }
        publishDogPlan("Listen", AnimationChannel.HEAD, loop = true, reason = "observer-speaking")
    }

    override fun onPartialText(text: String) {
        if (!openingGreetingInFlight) _uiState.update { it.copy(heardText = text) }
    }

    override fun onFinalText(text: String) {
        if (!openingGreetingInFlight) handleRecognizedText(text)
    }

    override fun onSpeechError(message: String, recoverable: Boolean) {
        if (!sessionStarted || openingGreetingInFlight) return
        _uiState.update { it.copy(mood = DogMood.SERIOUS, errorMessage = message) }
        if (recoverable) {
            recoverToListening(delayMs = 1_200L)
        } else {
            setPhase(CallPhase.ERROR)
        }
    }

    override fun onTtsPreparing(percent: Int, message: String) {
        if (!sessionStarted) setPhase(CallPhase.STARTING)
    }

    override fun onTtsReady() {
        ttsReady = true
        tryStartSession()
    }

    override fun onTtsStarted() {
        setPhase(CallPhase.SPEAKING)
    }

    override fun onViseme(viseme: MouthViseme) {
        _uiState.update { it.copy(viseme = viseme) }
    }

    override fun onTtsFinished() {
        _uiState.update { it.copy(viseme = MouthViseme.REST) }
        if (!microphonePermissionGranted || !sessionStarted) return
        if (openingGreetingInFlight) openingGreetingInFlight = false
        recoverToListening(delayMs = 140L)
    }

    override fun onTtsError(message: String) {
        openingGreetingInFlight = false
        _uiState.update {
            it.copy(
                viseme = MouthViseme.REST,
                mood = DogMood.SERIOUS,
                errorMessage = message
            )
        }
        if (microphonePermissionGranted && sessionStarted) {
            recoverToListening(delayMs = 1_200L)
        } else {
            setPhase(CallPhase.ERROR)
        }
    }

    private fun setPhase(phase: CallPhase) {
        officeSoundscape.setConversationPhase(phase)
        _uiState.update { it.copy(phase = phase) }
    }

    override fun onCleared() {
        stopAmbientLife()
        conversationJob?.cancel()
        RuntimeOfficePlanBus.clear()
        officeSoundscape.release()
        voiceEngine.release()
        super.onCleared()
    }

    private data class AmbientBeat(
        val cue: OfficeCue,
        val attention: DogAttention,
        val action: DogAction,
        val background: BackgroundActivity
    )

    private companion object {
        const val OPENING_GREETING = "هلا يا بطل، معك الشرطي. وش عندك؟"

        val AMBIENT_BEATS = listOf(
            AmbientBeat(OfficeCue.PAPER_RUSTLE, DogAttention.PAPER, DogAction.REVIEW_FILE, BackgroundActivity.PAPERWORK),
            AmbientBeat(OfficeCue.FOOTSTEPS, DogAttention.STAFF, DogAction.SEATED_IDLE, BackgroundActivity.STAFF_WALK),
            AmbientBeat(OfficeCue.PHONE_RING, DogAttention.PHONE, DogAction.ANSWER_PHONE, BackgroundActivity.CALM_WORK),
            AmbientBeat(OfficeCue.DOOR_OPEN, DogAttention.DOOR, DogAction.WALK_TO_DOOR, BackgroundActivity.DOOR_TRAFFIC),
            AmbientBeat(OfficeCue.DOOR_CLOSE, DogAttention.PAPER, DogAction.RETURN_TO_DESK, BackgroundActivity.CALM_WORK),
            AmbientBeat(OfficeCue.NONE, DogAttention.MONITOR, DogAction.SEATED_IDLE, BackgroundActivity.CALM_WORK)
        )
    }
}
