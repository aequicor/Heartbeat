package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `a leading directive is removed with its separator`() {
        val prompt = hostDirective("Call remember") + "\n\n/remember Use UTF-8"
        assertEquals("/remember Use UTF-8", stripHostDirectives(prompt))
    }

    @Test
    fun `text without directives is unchanged`() {
        assertEquals("plain  text \n", stripHostDirectives("plain  text \n"))
        assertEquals("prompt", withHostDirectives("prompt", emptyList()))
    }
}
