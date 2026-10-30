package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises zlib rather than the JVM substitute, using a deliberately small output buffer. */
class RawInflaterTest {
    @Test
    fun pinnedInputSurvivesMultipleInflateCalls() {
        val inflater = RawInflater()
        try {
            // Raw DEFLATE for "hello world", generated independently with Python zlib.
            val compressed = "cb48cdc9c95728cf2fca490100".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            inflater.input(compressed)
            val output = ArrayList<Byte>()
            val chunk = ByteArray(2)
            repeat(8) {
                if (!inflater.finished()) {
                    val count = inflater.inflate(chunk)
                    output.addAll(chunk.take(count))
                }
            }
            assertTrue(inflater.finished())
            assertEquals("hello world", output.toByteArray().decodeToString())
        } finally {
            inflater.end()
        }
    }
}
