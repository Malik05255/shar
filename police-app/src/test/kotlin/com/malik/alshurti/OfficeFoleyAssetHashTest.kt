package com.malik.alshurti

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeFoleyAssetHashTest {
    @Test
    fun `bundled Foley bytes exactly match approved artifact`() {
        val expected = mapOf(
            "foley_phone_ring.ogg" to "ef116d48e4ec85db965e25f9871882ed73ba7cb3a1dea264ed48214d0fdd3905",
            "foley_door_open.ogg" to "c17b3ffc177ac930f67a3e34d6670500cce92e7dcad3a20f3805ece495b995c3",
            "foley_door_close.ogg" to "634d702a90f9e89a284de5738708e3d77782ed15914facc66693ca0e0b5c55dc",
            "foley_page_turn.ogg" to "a081c8d0572c8cff6a107ddbf1c3ded29908d2e5979b935eb223940fe7e27e76"
        )

        expected.forEach { (name, expectedSha256) ->
            val asset = File("src/main/res/raw/$name")
            assertTrue("Missing approved Foley asset: $name", asset.isFile)
            assertEquals("Unexpected bytes for $name", expectedSha256, sha256(asset))
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
