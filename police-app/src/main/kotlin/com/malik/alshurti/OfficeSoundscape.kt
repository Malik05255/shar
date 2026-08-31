package com.malik.alshurti

import android.content.Context

/**
 * Real-foley gate for the living office.
 *
 * The previous procedural effects were mathematically generated tones/noise. Even when they were
 * event-only, a phone or door cue could still be perceived as an artificial recording tone. For
 * the device-validation build the baseline is therefore absolute silence outside character speech.
 * Real spatial Foley will be reintroduced only from recorded/cinematic assets tied to visible
 * physical events; no oscillator, synthetic ring, HVAC bed or repeating ambience is allowed.
 */
class OfficeSoundscape(context: Context) {
    @Suppress("UNUSED_PARAMETER")
    private val appContext = context.applicationContext

    fun start() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun setConversationPhase(phase: CallPhase) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun playCue(cue: OfficeCue) = Unit

    fun release() = Unit
}
