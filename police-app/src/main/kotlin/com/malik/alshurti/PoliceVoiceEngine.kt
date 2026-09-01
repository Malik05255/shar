package com.malik.alshurti

import android.content.Context
import com.malik.alshurti.neural.NeuralArabicVoice
import com.malik.alshurti.neural.PcmSpeechEnergy
import com.malik.alshurti.voice.BundledNaturalVoice
import com.malik.alshurti.voice.GeminiSilentListener
import com.malik.alshurti.voice.OfflineArabicListener
import com.malik.alshurti.voice.SaudiHumanVoice

/**
 * Single resilient half-duplex voice coordinator.
 *
 * ONLINE always prefers the cloud ASR/TTS path. Local models are prepared in the background and are
 * used only as a recovery path. A failure of one cloud utterance never disables cloud audio for the
 * rest of the session. Every arbitrary reply therefore has three output chances:
 * Gemini -> exact bundled emergency clip -> local neural voice.
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

    private var localAsrReady = false
    private var localTtsReady = false
    private var cloudTtsReady = false
    private var activeSpeechBackend = SpeechBackend.NONE
    private var activeListenBackend = ListenBackend.NONE
    private var localListenFallbackUsed = false
    private val attemptedSpeechBackends = linkedSetOf<SpeechBackend>()

    private var lastViseme = MouthViseme.REST
    private var lipEnergy = 0f
    private var lipVoiced = false

    private val cloudAvailable: Boolean
        get() = BuildConfig.GEMINI_API_KEY.trim().isNotBlank()

    private val cloudListener = GeminiSilentListener(
        context = appContext,
        callbacks = object : GeminiSilentListener.Callbacks {
            override fun onReady() {
                if (released || activeListenBackend != ListenBackend.CLOUD) return
                listener.onReadyToListen()
            }

            override fun onSpeechStarted() {
                if (released || activeListenBackend != ListenBackend.CLOUD) return
                listener.onSpeechStarted()
            }

            override fun onFinalText(text: String) {
                if (released || activeListenBackend != ListenBackend.CLOUD) return
                activeListenBackend = ListenBackend.NONE
                localListenFallbackUsed = false
                listener.onFinalText(text)
            }

            override fun onError(message: String, recoverable: Boolean) {
                if (released || activeListenBackend != ListenBackend.CLOUD) return
                activeListenBackend = ListenBackend.NONE
                if (mode == VoiceMode.ONLINE && localAsrReady && !localListenFallbackUsed) {
                    localListenFallbackUsed = true
                    activeListenBackend = ListenBackend.LOCAL
                    localListener.start()
                } else {
                    listener.onSpeechError(message, recoverable || localAsrReady)
                }
            }
        }
    )

    private val localListener = OfflineArabicListener(
        context = appContext,
        callbacks = object : OfflineArabicListener.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (!readyReported && mode == VoiceMode.OFFLINE) listener.onTtsPreparing(percent, message)
            }

            override fun onPrepared() {
                localAsrReady = true
                maybeReportReady()
            }

            override fun onReady() {
                if (released || activeListenBackend != ListenBackend.LOCAL) return
                listener.onReadyToListen()
            }

            override fun onSpeechStarted() {
                if (released || activeListenBackend != ListenBackend.LOCAL) return
                listener.onSpeechStarted()
            }

            override fun onFinalText(text: String) {
                if (released || activeListenBackend != ListenBackend.LOCAL) return
                activeListenBackend = ListenBackend.NONE
                localListenFallbackUsed = false
                listener.onFinalText(text)
            }

            override fun onError(message: String, recoverable: Boolean) {
                if (activeListenBackend == ListenBackend.LOCAL) {
                    activeListenBackend = ListenBackend.NONE
                    listener.onSpeechError(message, recoverable)
                } else if (mode == VoiceMode.OFFLINE) {
                    localAsrReady = false
                    listener.onSpeechError(message, recoverable)
                }
            }
        }
    )

    private val cloudVoice = SaudiHumanVoice(
        context = appContext,
        callbacks = object : SaudiHumanVoice.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (!readyReported && mode == VoiceMode.ONLINE && activeSpeechBackend == SpeechBackend.NONE) {
                    listener.onTtsPreparing(percent, message)
                }
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
                    // Do not poison the entire session because one request/playback failed.
                    failActiveSpeechBackend(message, keepBackendReady = true)
                } else {
                    cloudTtsReady = false
                    maybeReportReady()
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
                    failActiveSpeechBackend(message, keepBackendReady = true)
                }
            }
        }
    )

    private val localVoice = NeuralArabicVoice(
        context = appContext,
        callbacks = object : NeuralArabicVoice.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (!readyReported && mode == VoiceMode.OFFLINE) listener.onTtsPreparing(percent, message)
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
                if (activeSpeechBackend == SpeechBackend.LOCAL) {
                    localTtsReady = false
                    failActiveSpeechBackend(message, keepBackendReady = false)
                } else {
                    localTtsReady = false
                    maybeReportReady()
                }
            }
        }
    )

    fun localModelsInstalled(): Boolean = localListener.isModelInstalled() && localVoice.isModelInstalled()

    fun recommendedStartupMode(): VoiceMode =
        if (cloudAvailable || bundledVoice.has(STARTUP_PROBE_TEXT)) VoiceMode.ONLINE else VoiceMode.OFFLINE

    fun setMode(newMode: VoiceMode) {
        if (released) return
        stopListening()
        interruptSpeech()
        mode = newMode
        readyReported = false
        localListenFallbackUsed = false

        when (newMode) {
            VoiceMode.ONLINE -> {
                // Prepare both local recovery engines in the background. They must never steal the
                // primary online path just because their download happened to finish first.
                localListener.prepare(allowDownload = true)
                localVoice.prepare(allowDownload = true)
                if (cloudAvailable) cloudVoice.prepare() else cloudTtsReady = false
            }
            VoiceMode.OFFLINE -> {
                cloudTtsReady = false
                localListener.prepare(allowDownload = false)
                localVoice.prepare(allowDownload = false)
            }
        }
        maybeReportReady()
    }

    fun startListening() {
        if (released || activeListenBackend != ListenBackend.NONE) return
        localListenFallbackUsed = false
        when {
            mode == VoiceMode.ONLINE && cloudAvailable -> {
                activeListenBackend = ListenBackend.CLOUD
                cloudListener.start()
            }
            localAsrReady -> {
                activeListenBackend = ListenBackend.LOCAL
                localListener.start()
            }
            else -> listener.onSpeechError(
                if (mode == VoiceMode.OFFLINE) "الاستماع المحلي غير مجهز بعد." else "جاري تجهيز الاستماع.",
                true
            )
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
        attemptedSpeechBackends.clear()
        activeSpeechBackend = SpeechBackend.NONE
        if (spokenText.isBlank()) {
            listener.onTtsFinished()
            return
        }
        startNextSpeechBackend(null)
    }

    fun interruptSpeech() {
        attemptedSpeechBackends.clear()
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
        activeSpeechBackend = SpeechBackend.NONE
        attemptedSpeechBackends.clear()
        cloudListener.release()
        localListener.release()
        cloudVoice.release()
        bundledVoice.release()
        localVoice.release()
        resetMouth()
    }

    private fun startNextSpeechBackend(lastError: String?) {
        if (released || spokenText.isBlank()) return
        val next = when (mode) {
            VoiceMode.ONLINE -> when {
                cloudTtsReady && SpeechBackend.CLOUD !in attemptedSpeechBackends -> SpeechBackend.CLOUD
                bundledVoice.has(spokenText) && SpeechBackend.BUNDLED !in attemptedSpeechBackends -> SpeechBackend.BUNDLED
                localTtsReady && SpeechBackend.LOCAL !in attemptedSpeechBackends -> SpeechBackend.LOCAL
                else -> SpeechBackend.NONE
            }
            VoiceMode.OFFLINE -> when {
                localTtsReady && SpeechBackend.LOCAL !in attemptedSpeechBackends -> SpeechBackend.LOCAL
                bundledVoice.has(spokenText) && SpeechBackend.BUNDLED !in attemptedSpeechBackends -> SpeechBackend.BUNDLED
                else -> SpeechBackend.NONE
            }
        }

        if (next == SpeechBackend.NONE) {
            activeSpeechBackend = SpeechBackend.NONE
            resetMouth()
            listener.onTtsError(
                lastError ?: if (mode == VoiceMode.ONLINE) {
                    "تعذر تشغيل الصوت الآن."
                } else {
                    "الصوت المحلي غير جاهز بعد."
                }
            )
            return
        }

        activeSpeechBackend = next
        attemptedSpeechBackends += next
        when (next) {
            SpeechBackend.CLOUD -> cloudVoice.speak(spokenText)
            SpeechBackend.BUNDLED -> if (!bundledVoice.speak(spokenText)) {
                failActiveSpeechBackend("تعذر تشغيل الصوت المضمّن.", keepBackendReady = true)
            }
            SpeechBackend.LOCAL -> localVoice.speak(spokenText)
            SpeechBackend.NONE -> Unit
        }
    }

    private fun failActiveSpeechBackend(message: String, keepBackendReady: Boolean) {
        val failed = activeSpeechBackend
        activeSpeechBackend = SpeechBackend.NONE
        resetMouth()
        if (!keepBackendReady) {
            when (failed) {
                SpeechBackend.CLOUD -> cloudTtsReady = false
                SpeechBackend.LOCAL -> localTtsReady = false
                else -> Unit
            }
        }
        startNextSpeechBackend(message)
    }

    private fun maybeReportReady() {
        if (released || readyReported) return
        val inputReady = when (mode) {
            VoiceMode.ONLINE -> cloudAvailable || localAsrReady
            VoiceMode.OFFLINE -> localAsrReady
        }
        val outputReady = when (mode) {
            VoiceMode.ONLINE -> cloudTtsReady || bundledVoice.has(STARTUP_PROBE_TEXT) || localTtsReady
            VoiceMode.OFFLINE -> localTtsReady || bundledVoice.has(STARTUP_PROBE_TEXT)
        }
        if (inputReady && outputReady) {
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
        attemptedSpeechBackends.clear()
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
