package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepSeekVersionTest {
    @Test
    fun `dsh version output is parsed`() {
        assertEquals("0.2.1-rc.5", parseDeepSeekVersion("0.2.1-rc.5\n"))
        assertEquals("0.3.0", parseDeepSeekVersion("dsh 0.3.0"))
    }

    @Test
    fun `output without a version has no version`() {
        assertNull(parseDeepSeekVersion("command not found"))
        assertNull(parseDeepSeekVersion(""))
    }
}
