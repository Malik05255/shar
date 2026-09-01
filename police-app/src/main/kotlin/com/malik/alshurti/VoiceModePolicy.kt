package com.malik.alshurti

/**
 * Startup policy for the invisible voice transport.
 *
 * There is deliberately no mode picker in the cinematic UI. Network access is used only when one
 * of the local model packs still needs first-time provisioning. Once Whisper and Supertonic are
 * both present in app-private storage, the next launch is strictly local by default.
 */
object VoiceModePolicy {
    fun startupMode(localAsrInstalled: Boolean, localTtsInstalled: Boolean): VoiceMode =
        if (localAsrInstalled && localTtsInstalled) VoiceMode.OFFLINE else VoiceMode.ONLINE
}
