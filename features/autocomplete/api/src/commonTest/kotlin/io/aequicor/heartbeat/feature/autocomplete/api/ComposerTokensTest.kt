package io.aequicor.heartbeat.feature.autocomplete.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposerTokensTest {
    @Test
    fun `slash at line start is a command trigger`() {
        val trigger = composerTrigger("/rem", 4)
        assertEquals(ComposerTrigger.Command(0..3, "rem"), trigger)
        assertEquals(ComposerTrigger.Command(0..3, "r"), composerTrigger("/rem", 2))
        assertEquals(ComposerTrigger.Command(0..0, ""), composerTrigger("/", 1))
    }

    @Test
    fun `slash inside text is not a command`() {
        assertNull(composerTrigger("https://example.com", 18))
        assertNull(composerTrigger("run /remember now", 15))
        assertNull(composerTrigger("plain text", 5))
        assertNull(composerTrigger("", 0))
    }

    @Test
    fun `command may start on a later line`() {
        assertEquals(ComposerTrigger.Command(3..11, "reme"), composerTrigger("hi\n/remember", 8))
    }

    @Test
    fun `mention needs whitespace before and a typed query`() {
        assertEquals(ComposerTrigger.Mention(6..8, "sk"), composerTrigger("hello @sk", 9))
        assertEquals(ComposerTrigger.Mention(0..4, "file"), composerTrigger("@file", 5))
        assertNull(composerTrigger("mail a@b.com", 8))
        assertNull(composerTrigger("hello @", 7))
        assertNull(composerTrigger("hello", 6))
    }

    @Test
    fun `mention query may contain path characters and spans the whole word`() {
        assertEquals(ComposerTrigger.Mention(0..8, "src/comm"), composerTrigger("@src/comm", 9))
        val mid = composerTrigger("@file.md tail", 5)
        assertEquals(ComposerTrigger.Mention(0..7, "file"), mid)
    }
}
