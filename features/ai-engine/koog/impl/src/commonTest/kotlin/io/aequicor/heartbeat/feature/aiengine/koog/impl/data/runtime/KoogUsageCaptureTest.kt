package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KoogUsageCaptureTest {
    @Test
    fun `Anthropic counts input both cache categories and final cumulative output`() {
        val capture = KoogUsageCapture()
        capture.enable(true)
        capture.anthropic(START)
        assertNull(capture.total)
        capture.anthropic("""{"type":"message_delta","usage":{"output_tokens":10}}""")
        capture.anthropic("""{"type":"message_delta","usage":{"output_tokens":20}}""")
        assertEquals(137, capture.total)
        capture.reset()
        capture.anthropic("""{"type":"message_delta","usage":{"output_tokens":20}}""")
        assertNull(capture.total)
    }

    @Test
    fun `disabled malformed missing and negative native counters are never fabricated`() {
        val capture = KoogUsageCapture()
        capture.anthropic(START)
        assertNull(capture.total)
        capture.enable(true)
        capture.anthropic(START)
        capture.anthropic("""{"type":"message_delta","usage":{"output_tokens":-1}}""")
        assertNull(capture.total)
        capture.anthropic("""{"type":"message_delta","usage":{"output_tokens":20}}""")
        capture.enable(false)
        capture.enable(true)
        assertNull(capture.total)
        capture.anthropic("not json")
        assertNull(capture.total)
        assertNull(nativeTokenSum(Long.MAX_VALUE, 1))
    }
}

private val START = """
    {"type":"message_start","message":{"usage":{
      "input_tokens":7,"cache_creation_input_tokens":10,"cache_read_input_tokens":100,"output_tokens":0
    }}}
""".trimIndent()
