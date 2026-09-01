package com.malik.alshurti

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeOfficePlanBusTest {
    @Test
    fun `viseme channel updates independently and resets to rest`() {
        RuntimeOfficePlanBus.clear()
        assertEquals(MouthViseme.REST, RuntimeOfficePlanBus.viseme.value)

        RuntimeOfficePlanBus.publishViseme(MouthViseme.OPEN)
        assertEquals(MouthViseme.OPEN, RuntimeOfficePlanBus.viseme.value)

        RuntimeOfficePlanBus.publishViseme(MouthViseme.ROUND)
        assertEquals(MouthViseme.ROUND, RuntimeOfficePlanBus.viseme.value)

        RuntimeOfficePlanBus.clear()
        assertEquals(MouthViseme.REST, RuntimeOfficePlanBus.viseme.value)
    }
}
