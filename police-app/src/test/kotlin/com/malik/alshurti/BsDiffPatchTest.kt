package com.malik.alshurti

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class BsDiffPatchTest {
    @Test
    fun reconstructsTargetFromStandardBsdiff40Patch() {
        val dir = Files.createTempDirectory("alshorti-bsdiff-test").toFile()
        try {
            val old = File(dir, "old.apk").apply { writeBytes("abc".toByteArray()) }
            val patch = File(dir, "delta.bsdiff")
            val target = File(dir, "new.apk")

            // abc -> abdXYZ. First three bytes are old + [0,0,1], then XYZ is extra data.
            val control = bzip(offset(3) + offset(3) + offset(0))
            val diff = bzip(byteArrayOf(0, 0, 1))
            val extra = bzip("XYZ".toByteArray())

            patch.outputStream().use { output ->
                output.write("BSDIFF40".toByteArray(Charsets.US_ASCII))
                output.write(offset(control.size.toLong()))
                output.write(offset(diff.size.toLong()))
                output.write(offset(6))
                output.write(control)
                output.write(diff)
                output.write(extra)
            }

            BsDiffPatch.apply(old, patch, target)
            assertArrayEquals("abdXYZ".toByteArray(), target.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun bzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { buffer ->
        BZip2CompressorOutputStream(buffer).use { it.write(bytes) }
        buffer.toByteArray()
    }

    private fun offset(value: Long): ByteArray {
        val bytes = ByteArray(8)
        var remaining = if (value < 0) -value else value
        for (index in 0..7) {
            bytes[index] = (remaining and 0xff).toByte()
            remaining = remaining shr 8
        }
        if (value < 0) bytes[7] = (bytes[7].toInt() or 0x80).toByte()
        return bytes
    }
}
