package com.malik.alshurti

import android.content.Context
import com.malik.alshurti.neural.NeuralArabicVoice
import com.malik.alshurti.neural.PcmSpeechEnergy
import com.malik.alshurti.voice.BundledNaturalVoice
import com.malik.alshurti.voice.GeminiSilentListener
import com.malik.alshurti.voice.OfflineArabicListener
import com.malik.alshurti.voice.SaudiHumanVoice

/**
 * Deliberately simple voice runtime.
 *
 * ONLINE: Gemini ASR + Gemini TTS. The opening greeting is a bundled WAV so startup is immediate.
 * OFFLINE: bundled Whisper ASR + local neural TTS.
 *
 * No hidden cross-mode fallback is allowed. If a backend fails, the UI receives the real error.
 */
class PoliceVoiceEngine(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onReadyToListen()
        fun onSpeechStarted()
        fun onPartialText(text: String)
        fun onFinalText(text: String)
        fun onSpeechError(message: String, recoverable: Boolean)
        fun onTtsPreparing(percent: Int, message: String)
        fun onTtsReady()
        fun onTtsStarted()
        fun onTtsFinished()
        fun onTtsError(message: String)
        fun onViseme(viseme: MouthViseme)
    }

    private enum class SpeechBackend { NONE, CLOUD, BUNDLED, LOCAL }
    private enum class ListenBackend { NONE, CLOUD, LOCAL }

    private val appContext = context.applicationContext
    private var mode = VoiceMode.ONLINE
    private var released = false
    private var readyReported = false
    private var spokenText = ""
    private var activeSpeechBackend = SpeechBackend.NONE
    private var activeListenBackend = ListenBackend.NONE
    private var localAsrReady = false
    private var localTtsReady = false
    private var cloudTtsReady = false
    private var lastViseme = MouthViseme.REST
    private var lipEnergy = 0f
    private var lipVoiced = false

    private val cloudAvailable: Boolean
        get() = BuildConfig.GEMINI_API_KEY.trim().isNotBlank()

    private val cloudListener = GeminiSilentListener(
        context = appContext,
        callbacks = object : GeminiSilentListener.Callbacks {
            override fun onReady() {
                if (!released && activeListenBackend == ListenBackend.CLOUD) listener.onReadyToListen()
            }

            override fun onSpeechStarted() {
                if (!released && activeListenBackend == ListenBackend.CLOUD) listener.onSpeechStarted()
            }

            override fun onFinalText(text: String) {
                if (released || activeListenBackend != ListenBackend.CLOUD) return
                activeListenBackend = ListenBackend.NONE
                listener.onFinalText(text)
            }

            override fun onError(message: String, recoverable: Boolean) {
                if (released || activeListenBackend != ListenBackend.CLOUD) return
                activeListenBackend = ListenBackend.NONE
                listener.onSpeechError(message, recoverable)
            }
        }
    )

    private val localListener = OfflineArabicListener(
        context = appContext,
        callbacks = object : OfflineArabicListener.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (mode == VoiceMode.OFFLINE) listener.onTtsPreparing(percent, message)
            }

            override fun onPrepared() {
                localAsrReady = true
                maybeReportReady()
            }

            override fun onReady() {
                if (!released && activeListenBackend == ListenBackend.LOCAL) listener.onReadyToListen()
            }

            override fun onSpeechStarted() {
                if (!released && activeListenBackend == ListenBackend.LOCAL) listener.onSpeechStarted()
            }

            override fun onFinalText(text: String) {
                if (released || activeListenBackend != ListenBackend.LOCAL) return
                activeListenBackend = ListenBackend.NONE
                listener.onFinalText(text)
            }

            override fun onError(message: String, recoverable: Boolean) {
                if (activeListenBackend == ListenBackend.LOCAL) activeListenBackend = ListenBackend.NONE
                listener.onSpeechError(message, recoverable)
            }
        }
    )

    private val cloudVoice = SaudiHumanVoice(
        context = appContext,
        callbacks = object : SaudiHumanVoice.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (mode == VoiceMode.ONLINE) listener.onTtsPreparing(percent, message)
            }

            override fun onReady() {
                cloudTtsReady = true
                maybeReportReady()
            }

            override fun onSpeechStarted(durationMs: Long) {
                if (activeSpeechBackend == SpeechBackend.CLOUD) handleSpeechStarted()
            }

            override fun onSpeechCursor(fraction: Float) {
                if (activeSpeechBackend == SpeechBackend.CLOUD) handleSpeechCursor(fraction)
            }

            override fun onSpeechFrame(fraction: Float, energy: Float) {
                if (activeSpeechBackend == SpeechBackend.CLOUD) handleEnergySpeechFrame(fraction, energy)
            }

            override fun onSpeechFinished() {
                if (activeSpeechBackend == SpeechBackend.CLOUD) handleSpeechFinished()
            }

            override fun onError(message: String) {
                if (activeSpeechBackend == SpeechBackend.CLOUD) {
                    activeSpeechBackend = SpeechBackend.NONE
                    resetMouth()
                    if (bundledVoice.has(spokenText)) {
                        startBundled()
                    } else {
                        listener.onTtsError(message)
                    }
                } else {
                    cloudTtsReady = false
                    listener.onTtsError(message)
                }
            }
        }
    )

    private val bundledVoice = BundledNaturalVoice(
        context = appContext,
        callbacks = object : BundledNaturalVoice.Callbacks {
            override fun onSpeechStarted(durationMs: Long) {
                if (activeSpeechBackend == SpeechBackend.BUNDLED) handleSpeechStarted()
            }

            override fun onSpeechCursor(fraction: Float) {
                if (activeSpeechBackend == SpeechBackend.BUNDLED) handleSpeechCursor(fraction)
            }

            override fun onSpeechFinished() {
                if (activeSpeechBackend == SpeechBackend.BUNDLED) handleSpeechFinished()
            }

            override fun onError(message: String) {
                if (activeSpeechBackend == SpeechBackend.BUNDLED) {
                    activeSpeechBackend = SpeechBackend.NONE
                    resetMouth()
                    listener.onTtsError(message)
                }
            }
        }
    )

    private val localVoice = NeuralArabicVoice(
        context = appContext,
        callbacks = object : NeuralArabicVoice.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (mode == VoiceMode.OFFLINE) listener.onTtsPreparing(percent, message)
            }

            override fun onReady() {
                localTtsReady = true
                maybeReportReady()
            }

            override fun onSpeechStarted(durationMs: Long) {
                if (activeSpeechBackend == SpeechBackend.LOCAL) handleSpeechStarted()
            }

            override fun onSpeechFrame(fraction: Float, energy: Float) {
                if (activeSpeechBackend == SpeechBackend.LOCAL) handleEnergySpeechFrame(fraction, energy)
            }

            override fun onSpeechFinished() {
                if (activeSpeechBackend == SpeechBackend.LOCAL) handleSpeechFinished()
            }

            override fun onError(message: String) {
                if (activeSpeechBackend == SpeechBackend.LOCAL) activeSpeechBackend = SpeechBackend.NONE
                localTtsReady = false
                resetMouth()
                if (bundledVoice.has(spokenText)) startBundled() else listener.onTtsError(message)
            }
        }
    )

    fun localModelsInstalled(): Boolean = localListener.isModelInstalled() && localVoice.isModelInstalled()

    fun recommendedStartupMode(): VoiceMode = if (cloudAvailable) VoiceMode.ONLINE else VoiceMode.OFFLINE

    fun setMode(newMode: VoiceMode) {
        if (released) return
        stopListening()
        interruptSpeech()
        mode = newMode
        readyReported = false

        when (mode) {
            VoiceMode.ONLINE -> {
                // Do not touch the heavyweight local engines in online mode.
                if (!cloudAvailable) {
                    listener.onTtsError("مفتاح Gemini غير موجود في هذه النسخة.")
                    return
                }
                cloudVoice.prepare()
                maybeReportReady()
            }
            VoiceMode.OFFLINE -> {
                cloudTtsReady = false
                localListener.prepare(allowDownload = false)
                localVoice.prepare(allowDownload = false)
                maybeReportReady()
            }
        }
    }

    fun startListening() {
        if (released || activeListenBackend != ListenBackend.NONE) return
        when (mode) {
            VoiceMode.ONLINE -> {
                if (!cloudAvailable) {
                    listener.onSpeechError("Gemini غير مهيأ في هذه النسخة.", false)
                    return
                }
                activeListenBackend = ListenBackend.CLOUD
                cloudListener.start()
            }
            VoiceMode.OFFLINE -> {
                if (!localAsrReady) {
                    listener.onSpeechError("الاستماع المحلي غير جاهز بعد.", true)
                    return
                }
                activeListenBackend = ListenBackend.LOCAL
                localListener.start()
            }
        }
    }

    fun stopListening() {
        activeListenBackend = ListenBackend.NONE
        cloudListener.stop()
        localListener.stop()
    }

    fun speak(text: String) {
        if (released) return
        spokenText = text.trim()
        stopListening()
        interruptSpeech()
        if (spokenText.isBlank()) {
            listener.onTtsFinished()
            return
        }

        when (mode) {
            VoiceMode.ONLINE -> {
                if (spokenText == STARTUP_PROBE_TEXT && bundledVoice.has(spokenText)) {
                    startBundled()
                } else if (cloudTtsReady) {
                    activeSpeechBackend = SpeechBackend.CLOUD
                    cloudVoice.speak(spokenText)
                } else if (bundledVoice.has(spokenText)) {
                    startBundled()
                } else {
                    listener.onTtsError("صوت Gemini غير جاهز.")
                }
            }
            VoiceMode.OFFLINE -> {
                if (localTtsReady) {
                    activeSpeechBackend = SpeechBackend.LOCAL
                    localVoice.speak(spokenText)
                } else if (bundledVoice.has(spokenText)) {
                    startBundled()
                } else {
                    listener.onTtsError("الصوت المحلي غير جاهز بعد.")
                }
            }
        }
    }

    private fun startBundled() {
        activeSpeechBackend = SpeechBackend.BUNDLED
        if (!bundledVoice.speak(spokenText)) {
            activeSpeechBackend = SpeechBackend.NONE
            listener.onTtsError("تعذر تشغيل الملف الصوتي المحلي.")
        }
    }

    fun interruptSpeech() {
        activeSpeechBackend = SpeechBackend.NONE
        cloudVoice.interrupt()
        bundledVoice.interrupt()
        localVoice.interrupt()
        resetMouth()
    }

    fun release() {
        if (released) return
        released = true
        stopListening()
        interruptSpeech()
        cloudListener.release()
        localListener.release()
        cloudVoice.release()
        bundledVoice.release()
        localVoice.release()
    }

    private fun maybeReportReady() {
        if (released || readyReported) return
        val ready = when (mode) {
            VoiceMode.ONLINE -> cloudAvailable && (cloudTtsReady || bundledVoice.has(STARTUP_PROBE_TEXT))
            VoiceMode.OFFLINE -> localAsrReady && (localTtsReady || bundledVoice.has(STARTUP_PROBE_TEXT))
        }
        if (ready) {
            readyReported = true
            listener.onTtsReady()
        }
    }

    private fun handleSpeechStarted() {
        lipEnergy = 0f
        lipVoiced = false
        lastViseme = MouthViseme.REST
        dispatchViseme(MouthViseme.REST)
        listener.onTtsStarted()
    }

    private fun handleEnergySpeechFrame(fraction: Float, energy: Float) {
        lipEnergy = PcmSpeechEnergy.smooth(lipEnergy, energy.coerceIn(0f, 1f))
        lipVoiced = PcmSpeechEnergy.isVoiced(lipEnergy, lipVoiced)
        val viseme = if (lipVoiced) visemeAtFraction(spokenText, fraction) else MouthViseme.REST
        if (viseme != lastViseme) {
            lastViseme = viseme
            dispatchViseme(viseme)
        }
    }

    private fun handleSpeechCursor(fraction: Float) {
        val viseme = visemeAtFraction(spokenText, fraction)
        if (viseme != lastViseme) {
            lastViseme = viseme
            dispatchViseme(viseme)
        }
    }

    private fun handleSpeechFinished() {
        activeSpeechBackend = SpeechBackend.NONE
        resetMouth()
        listener.onTtsFinished()
    }

    private fun resetMouth() {
        lipEnergy = 0f
        lipVoiced = false
        lastViseme = MouthViseme.REST
        dispatchViseme(MouthViseme.REST)
    }

    private fun dispatchViseme(viseme: MouthViseme) {
        RuntimeOfficePlanBus.publishViseme(viseme)
        listener.onViseme(viseme)
    }

    private fun visemeAtFraction(text: String, fraction: Float): MouthViseme {
        if (text.isBlank()) return MouthViseme.REST
        val position = ((text.length - 1) * fraction.coerceIn(0f, 1f)).toInt()
        val from = (position - 3).coerceAtLeast(0)
        val to = (position + 4).coerceAtMost(text.length)
        val letter = text.substring(from, to).firstOrNull(Char::isLetter) ?: return MouthViseme.REST
        return when (letter) {
            'ب', 'م', 'ف' -> MouthViseme.CLOSED
            'و', 'ؤ' -> MouthViseme.ROUND
            'ي', 'ى', 'س', 'ش', 'ث', 'ز', 'ج' -> MouthViseme.WIDE
            'ا', 'أ', 'إ', 'آ', 'ع', 'ه', 'ح', 'خ', 'ق', 'ك' -> MouthViseme.OPEN
            else -> MouthViseme.OPEN
        }
    }

    private companion object {
        const val STARTUP_PROBE_TEXT = "هلا يا بطل، معك الشرطي. وش عندك؟"
    }
}
