package com.malik.alshurti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Runtime3DContentPackManagerTest {
    @Test
    fun semanticVersionComparisonHandlesPatchAndMinorVersions() {
        assertTrue(Runtime3DContentPackManager.compareVersionNames("0.6.3", "0.6.2") > 0)
        assertTrue(Runtime3DContentPackManager.compareVersionNames("0.7.0", "0.6.99") > 0)
        assertTrue(Runtime3DContentPackManager.compareVersionNames("1.0.0", "0.99.99") > 0)
        assertTrue(Runtime3DContentPackManager.compareVersionNames("0.6.2", "0.6.3") < 0)
    }

    @Test
    fun semanticVersionComparisonIgnoresBuildSuffixForCompatibilityGate() {
        assertEquals(0, Runtime3DContentPackManager.compareVersionNames("0.6.3-debug", "0.6.3"))
        assertEquals(0, Runtime3DContentPackManager.compareVersionNames("0.6.3", "0.6.3-release"))
    }
}
