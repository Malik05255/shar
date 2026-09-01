package com.malik.alshurti

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.malik.alshurti.neural.NeuralArabicVoice
import com.malik.alshurti.neural.PcmSpeechEnergy
import com.malik.alshurti.voice.BundledNaturalVoice
import com.malik.alshurti.voice.GeminiSilentListener
import com.malik.alshurti.voice.OfflineArabicListener
import com.malik.alshurti.voice.SaudiHumanVoice

/**
 * Natural-voice half-duplex coordinator.
 *
 * ONLINE prefers pre-generated Gemini Saudi speech bundled in the APK, then direct Gemini for
 * uncatalogued text. OFFLINE uses the local neural voice. Android platform speech is forbidden.
 */
class PoliceVoiceEngine(
    private val context: Context,
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

    private enum class SpeechBackend { NONE, BUNDLED, CLOUD, LOCAL }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mode: VoiceMode = VoiceMode.ONLINE
    private var spokenText = ""
    private var lastViseme = MouthViseme.REST
    private var localLipEnergy = 0f
    private var localLipVoiced = false
    private var observerHasSpoken = false
    private var released = false
    private var readyReported = false
    private var localTtsReady = false
    private var cloudTtsReady = false
    private var localAsrReady = false
    private var activeSpeechBackend = SpeechBackend.NONE
    private val attemptedSpeechBackends = linkedSetOf<SpeechBackend>()

    private val cloudAvailable: Boolean
        get() = BuildConfig.GEMINI_API_KEY.trim().isNotBlank()

    private val silentListener: GeminiSilentListener = GeminiSilentListener(
        context = context.applicationContext,
        callbacks = object : GeminiSilentListener.Callbacks {
            override fun onReady() {
                listener.onReadyToListen()
            }

            override fun onSpeechStarted() {
                observerHasSpoken = true
                listener.onSpeechStarted()
            }

            override fun onFinalText(text: String) {
                if (text.isNotBlank()) observerHasSpoken = true
                listener.onFinalText(text)
            }

            override fun onError(message: String, recoverable: Boolean) {
                listener.onSpeechError(message, recoverable)
            }
        }
    )

    private val offlineListener: OfflineArabicListener = OfflineArabicListener(
        context = context.applicationContext,
        callbacks = object : OfflineArabicListener.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (!readyReported && mode == VoiceMode.OFFLINE) listener.onTtsPreparing(percent, message)
            }

            override fun onPrepared() {
                localAsrReady = true
                maybeReportReady()
            }

            override fun onReady() {
                listener.onReadyToListen()
            }

            override fun onSpeechStarted() {
                observerHasSpoken = true
                listener.onSpeechStarted()
            }

            override fun onFinalText(text: String) {
                if (text.isNotBlank()) observerHasSpoken = true
                listener.onFinalText(text)
            }

            override fun onError(message: String, recoverable: Boolean) {
                if (!localAsrReady && mode == VoiceMode.ONLINE && cloudAvailable) {
                    maybeReportReady()
                } else {
                    listener.onSpeechError(message, recoverable)
                }
            }
        }
    )

    private val localVoice: NeuralArabicVoice = NeuralArabicVoice(
        context = context.applicationContext,
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
                    failActiveSpeechBackend(message)
                } else {
                    localTtsReady = false
                    maybeReportReady()
                }
            }
        }
    )

    private val bundledVoice: BundledNaturalVoice = BundledNaturalVoice(
        context = context.applicationContext,
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
                if (activeSpeechBackend == SpeechBackend.BUNDLED) failActiveSpeechBackend(message)
            }
        }
    )

    private val saudiVoice: SaudiHumanVoice = SaudiHumanVoice(
        context = context.applicationContext,
        callbacks = object : SaudiHumanVoice.Callbacks {
            override fun onPreparing(percent: Int, message: String) {
                if (!readyReported && mode == VoiceMode.ONLINE) listener.onTtsPreparing(percent, message)
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
                    failActiveSpeechBackend(message)
                } else {
                    cloudTtsReady = false
                    maybeReportReady()
                }
            }
        }
    )

    fun localModelsInstalled(): Boolean =
        offlineListener.isModelInstalled() && localVoice.isModelInstalled()

    fun recommendedStartupMode(): VoiceMode =
        if (bundledVoice.has(STARTUP_PROBE_TEXT) || cloudAvailable) VoiceMode.ONLINE else VoiceMode.OFFLINE

    fun setMode(newMode: VoiceMode) {
        mode = newMode
        stopListening()
        interruptSpeech()
        observerHasSpoken = false
        readyReported = false

        when (newMode) {
            VoiceMode.OFFLINE -> {
                cloudTtsReady = false
                offlineListener.prepare(allowDownload = false)
                localVoice.prepare(allowDownload = false)
            }
            VoiceMode.ONLINE -> {
                localTtsReady = false
                offlineListener.prepare(allowDownload = true)
                if (cloudAvailable) saudiVoice.prepare() else cloudTtsReady = false
            }
        }
        maybeReportReady()
    }

    fun startListening() {
        when {
            localAsrReady -> offlineListener.start()
            mode == VoiceMode.ONLINE && cloudAvailable -> silentListener.start()
            else -> listener.onSpeechError(
                if (mode == VoiceMode.OFFLINE) "الاستماع المحلي غير مجهز بعد." else "جاري تجهيز الاستماع.",
                true
            )
        }
    }

    fun stopListening() {
        offlineListener.stop()
        silentListener.stop()
    }

    fun interruptSpeech() {
        mainHandler.removeCallbacksAndMessages(null)
        attemptedSpeechBackends.clear()
        bundledVoice.interrupt()
        localVoice.interrupt()
        saudiVoice.interrupt()
        activeSpeechBackend = SpeechBackend.NONE
        resetMouth()
    }

    fun speak(text: String) {
        spokenText = text.trim()
        attemptedSpeechBackends.clear()
        stopListening()

        if (spokenText.isBlank()) {
            listener.onTtsFinished()
            return
        }
        startNextSpeechBackend(null)
    }

    fun release() {
        released = true
        attemptedSpeechBackends.clear()
        mainHandler.removeCallbacksAndMessages(null)
        stopListening()
        offlineListener.release()
        silentListener.release()
        bundledVoice.release()
        localVoice.release()
        saudiVoice.release()
        activeSpeechBackend = SpeechBackend.NONE
        resetMouth()
    }

    private fun startNextSpeechBackend(lastError: String?) {
        if (released || spokenText.isBlank()) return

        val next = when (mode) {
            VoiceMode.ONLINE -> when {
                bundledVoice.has(spokenText) && SpeechBackend.BUNDLED !in attemptedSpeechBackends -> SpeechBackend.BUNDLED
                cloudTtsReady && SpeechBackend.CLOUD !in attemptedSpeechBackends -> SpeechBackend.CLOUD
                else -> SpeechBackend.NONE
            }
            VoiceMode.OFFLINE -> if (
                localTtsReady && SpeechBackend.LOCAL !in attemptedSpeechBackends
            ) SpeechBackend.LOCAL else SpeechBackend.NONE
        }

        if (next == SpeechBackend.NONE) {
            activeSpeechBackend = SpeechBackend.NONE
            resetMouth()
            val message = lastError ?: when (mode) {
                VoiceMode.ONLINE -> "الصوت السعودي الطبيعي غير جاهز لهذه الجملة."
                VoiceMode.OFFLINE -> "الصوت العصبي المحلي غير جاهز بعد."
            }
            listener.onTtsError(message)
            return
        }

        activeSpeechBackend = next
        attemptedSpeechBackends += next
        when (next) {
            SpeechBackend.BUNDLED -> if (!bundledVoice.speak(spokenText)) {
                failActiveSpeechBackend("تعذر تشغيل الصوت الطبيعي المضمّن.")
            }
            SpeechBackend.CLOUD -> saudiVoice.speak(spokenText)
            SpeechBackend.LOCAL -> localVoice.speak(spokenText)
            SpeechBackend.NONE -> Unit
        }
    }

    private fun failActiveSpeechBackend(message: String) {
        val failed = activeSpeechBackend
        activeSpeechBackend = SpeechBackend.NONE
        resetMouth()
        when (failed) {
            SpeechBackend.CLOUD -> cloudTtsReady = false
            SpeechBackend.LOCAL -> localTtsReady = false
            SpeechBackend.BUNDLED,
            SpeechBackend.NONE -> Unit
        }
        startNextSpeechBackend(message)
    }

    private fun maybeReportReady() {
        if (released || readyReported) return
        val inputReady = localAsrReady || (mode == VoiceMode.ONLINE && cloudAvailable)
        val outputReady = when (mode) {
            VoiceMode.ONLINE -> bundledVoice.has(STARTUP_PROBE_TEXT) || cloudTtsReady
            VoiceMode.OFFLINE -> localTtsReady
        }
        if (inputReady && outputReady) {
            readyReported = true
            listener.onTtsReady()
        }
    }

    private fun handleSpeechStarted() {
        localLipEnergy = 0f
        localLipVoiced = false
        if (lastViseme != MouthViseme.REST) {
            lastViseme = MouthViseme.REST
            dispatchViseme(MouthViseme.REST)
        }
        listener.onTtsStarted()
    }

    private fun handleEnergySpeechFrame(fraction: Float, energy: Float) {
        localLipEnergy = PcmSpeechEnergy.smooth(localLipEnergy, energy.coerceIn(0f, 1f))
        localLipVoiced = PcmSpeechEnergy.isVoiced(localLipEnergy, localLipVoiced)
        val viseme = if (localLipVoiced) visemeAtFraction(spokenText, fraction) else MouthViseme.REST
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

    private fun dispatchViseme(viseme: MouthViseme) {
        RuntimeOfficePlanBus.publishViseme(viseme)
        listener.onViseme(viseme)
    }

    private fun resetMouth() {
        localLipEnergy = 0f
        localLipVoiced = false
        lastViseme = MouthViseme.REST
        dispatchViseme(MouthViseme.REST)
    }

    private fun visemeAtFraction(text: String, fraction: Float): MouthViseme {
        if (text.isBlank()) return MouthViseme.REST
        val position = ((text.length - 1) * fraction.coerceIn(0f, 1f)).toInt()
        val radius = 3
        val from = (position - radius).coerceAtLeast(0)
        val to = (position + radius + 1).coerceAtMost(text.length)
        val letter = text.substring(from, to)
            .firstOrNull { it.isLetter() }
            ?: return MouthViseme.REST
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
