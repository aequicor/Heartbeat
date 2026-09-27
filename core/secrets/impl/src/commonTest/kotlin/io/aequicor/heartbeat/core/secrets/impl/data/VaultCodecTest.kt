package io.aequicor.heartbeat.core.secrets.impl.data

import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VaultCodecTest {
    private val codec = VaultCodec()

    @Test
    fun readsExistingVersionOneFormatAndRoundTripsIt() {
        val reference = """{"type":"engine","id":"one","slot":"api-key","key":"key"}"""
        val bytes = """{"values":{"key":["v"]},"references":[$reference]}"""
            .encodeToByteArray()
        codec.decode(bytes).use { snapshot ->
            val key = SecretKey("key")
            val usage = SecretUsage("engine", "one", "api-key")
            assertEquals("v", snapshot.values.getValue(key).concatToString())
            assertEquals(key, snapshot.references[usage])
            codec.decode(codec.encode(snapshot)).use { restored ->
                assertEquals("v", restored.values.getValue(key).concatToString())
                assertEquals(snapshot.references, restored.references)
            }
        }
    }

    @Test
    fun rejectsUnknownVersionInvalidIdentifiersDanglingAndDuplicateReferences() {
        val reference = """{"type":"engine","id":"one","slot":"api-key","key":"key"}"""
        val malformed = listOf(
            """{"version":2}""",
            """{"values":{"invalid id":["v"]}}""",
            """{"references":[$reference]}""",
            """{"values":{"key":["v"]},"references":[$reference,$reference]}""",
            """{"values":{"key":["v"]},"references":[{"type":"","id":"one","slot":"api-key","key":"key"}]}""",
        )
        malformed.forEach { json ->
            assertFailsWith<Exception> { codec.decode(json.encodeToByteArray()) }
        }
    }
}
