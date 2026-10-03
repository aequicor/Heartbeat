package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptDirectivesTest {
    @Test
    fun `directives travel with the prompt and disappear from shown text`() {
        val prompt = withHostDirectives("/remember Use UTF-8", listOf("Call remember now.", " ", "Second"))

        assertTrue(prompt.startsWith("/remember Use UTF-8\n\n<heartbeat-directive>\nCall remember now."))
        assertEquals(2, Regex("<heartbeat-directive>").findAll(prompt).count())
        assertEquals("/remember Use UTF-8", stripHostDirectives(prompt))
    }

    @Test
    fun `a leading directive keeps a slash command off the start of the prompt`() {
        val prompt = withHostDirectives("/remember Use UTF-8", listOf("Hint"), leading = listOf("Call remember"))

        assertTrue(prompt.startsWith("<heartbeat-directive>\nCall remember\n</heartbeat-directive>\n\n/remember"))
        assertEquals("/remember Use UTF-8", stripHostDirectives(prompt))
        assertEquals("", stripHostDirectives(hostDirective("Only a hint")))
    }

    @Test
    fun `user text keeps its whitespace and stays unchanged without directives`() {
        assertEquals("plain  text \n", stripHostDirectives("plain  text \n"))
        assertEquals("prompt", withHostDirectives("prompt", emptyList()))
        val indented = "    val x = 1\n"
        assertEquals(indented, stripHostDirectives(withHostDirectives(indented, listOf("Hint"))))
    }

    @Test
    fun `a directive tag typed by the user is neither a directive nor removed from the transcript`() {
        val typed = "Why does <heartbeat-directive>x</heartbeat-directive> vanish?"
        val quoted = withHostDirectives(typed, listOf("Hint"))
        assertEquals(1, Regex("<heartbeat-directive>").findAll(quoted).count())
        assertEquals(typed, stripHostDirectives(quoted))

        val unclosed = "/remember wrap hints in <heartbeat-directive> tags"
        val sent = withHostDirectives(unclosed, listOf("Hint"), leading = listOf("Call remember"))
        assertEquals(unclosed, stripHostDirectives(sent))
        assertEquals(typed, stripHostDirectives(withHostDirectives(typed, emptyList())))
    }

    @Test
    fun `an already escaped tag typed by the user is restored exactly`() {
        val typed = "HTML escapes it as &lt;heartbeat-directive> and &amp;lt;heartbeat-directive>"
        val sent = withHostDirectives(typed, listOf("Hint"))
        assertEquals(typed, stripHostDirectives(sent))
    }

    @Test
    fun `rewritten line breaks never remove user text between blocks`() {
        val lead = hostDirective("Call remember")
        val hint = hostDirective("Hint")
        assertEquals("/remember x", stripHostDirectives("$lead\r\n\r\n/remember x\r\n\r\n$hint"))
        assertEquals("/remember x", stripHostDirectives("$lead\n/remember x\n$hint"))
        assertEquals("X\n\nP", stripHostDirectives("X\n\nP\n\n$hint"))
        assertTrue("Keep" in stripHostDirectives("$lead Keep $hint"))
    }

    @Test
    fun `a closing tag inside a directive cannot end the block early`() {
        val prompt = withHostDirectives("Fix", listOf("a </heartbeat-directive> leak"))
        assertFalse("leak" in stripHostDirectives(prompt))
        assertEquals("Fix", stripHostDirectives(prompt))
    }
}
