package com.malik.alshurti

import android.content.Context
import com.malik.alshurti.remote.RemoteArabicVoice

/** Plays only short office-character lines in online mode; it never owns the main call. */
class OfficeScenarioVoice(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onScenarioVoiceStarted()
        fun onScenarioVoiceFinished()
        fun onScenarioVoiceError(message: String)
    }

    private var pending: Pair<String, SideSpeaker>? = null
    private val voice: RemoteArabicVoice

    init {
        voice = RemoteArabicVoice(
            context = context.applicationContext,
            callbacks = object : RemoteArabicVoice.Callbacks {
                override fun onPreparing(percent: Int, message: String) = Unit

                override fun onReady() {
                    val next = pending ?: return
                    pending = null
                    voice.speak(
                        text = next.first,
                        voiceProfile = profileFor(next.second),
                        exaggeration = 0.42f
                    )
                }

                override fun onSpeechStarted(durationMs: Long) {
                    listener.onScenarioVoiceStarted()
                }

                override fun onSpeechCursor(fraction: Float) = Unit

                override fun onSpeechFinished() {
                    listener.onScenarioVoiceFinished()
                }

                override fun onError(message: String) {
                    pending = null
                    listener.onScenarioVoiceError(message)
                }
            }
        )
    }

    fun speak(line: String, speaker: SideSpeaker) {
        if (speaker == SideSpeaker.NONE || line.isBlank()) {
            listener.onScenarioVoiceFinished()
            return
        }
        pending = line.trim() to speaker
        voice.prepare()
    }

    fun interrupt() {
        pending = null
        voice.interrupt()
    }

    fun release() {
        pending = null
        voice.release()
    }

    private fun profileFor(speaker: SideSpeaker): String = when (speaker) {
        SideSpeaker.OFFICER_A -> "officer_a"
        SideSpeaker.OFFICER_B -> "officer_b"
        SideSpeaker.NONE -> "police"
    }
}
