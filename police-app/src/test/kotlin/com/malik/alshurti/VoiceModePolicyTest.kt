package com.malik.alshurti

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceModePolicyTest {
    @Test
    fun bothLocalModelsInstalledStartsOffline() {
        assertEquals(VoiceMode.OFFLINE, VoiceModePolicy.startupMode(true, true))
    }

    @Test
    fun missingEitherLocalModelAllowsOnlineProvisioning() {
        assertEquals(VoiceMode.ONLINE, VoiceModePolicy.startupMode(false, true))
        assertEquals(VoiceMode.ONLINE, VoiceModePolicy.startupMode(true, false))
        assertEquals(VoiceMode.ONLINE, VoiceModePolicy.startupMode(false, false))
    }
}
