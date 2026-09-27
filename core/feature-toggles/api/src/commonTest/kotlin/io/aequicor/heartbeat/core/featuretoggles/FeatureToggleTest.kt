package io.aequicor.heartbeat.core.featuretoggles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureToggleTest {

    @Test
    fun `the owner is the first segment of the key`() {
        assertEquals("chat", FeatureToggle.Flag("chat.streaming_responses", "Streaming").owner)
        assertEquals("ai", FeatureToggle.Flag("ai.models.experimental", "Models").owner)
    }

    @Test
    fun `a flag is off by default`() {
        assertFalse(FeatureToggle.Flag("chat.streaming", "Streaming").default)
    }

    @Test
    fun `keys must be dot-separated lowercase segments`() {
        val invalid = listOf(
            "chat",
            "Chat.streaming",
            "chat.",
            ".streaming",
            "chat..streaming",
            "chat.stream-ing",
            "1chat.x",
        )
        (invalid + ("a." + "b".repeat(127)))
            .forEach { key -> assertFailsWith<IllegalArgumentException>(key) { FeatureToggle.Flag(key, "d") } }
        FeatureToggle.Flag("a1.b_2.c", "d")
    }

    @Test
    fun `a description is required`() {
        assertFailsWith<IllegalArgumentException> { FeatureToggle.Flag("chat.streaming", " ") }
    }

    @Test
    fun `a choice defaults to its first option`() {
        assertEquals("fast", FeatureToggle.Choice("ai.mode", "Mode", listOf("fast", "smart")).default)
    }

    @Test
    fun `a choice rejects invalid options and defaults`() {
        assertFailsWith<IllegalArgumentException> { FeatureToggle.Choice("ai.mode", "Mode", emptyList()) }
        assertFailsWith<IllegalArgumentException> { FeatureToggle.Choice("ai.mode", "Mode", listOf("fast", " ")) }
        assertFailsWith<IllegalArgumentException> { FeatureToggle.Choice("ai.mode", "Mode", listOf("fast", "fast")) }
        assertFailsWith<IllegalArgumentException> { FeatureToggle.Choice("ai.mode", "Mode", listOf("fast"), "smart") }
    }

    @Test
    fun `a state is overridden only when set locally`() {
        val flag = FeatureToggle.Flag("chat.streaming", "Streaming")
        assertTrue(ToggleState(flag, false, ToggleSource.LocalOverride).isOverridden)
        assertFalse(ToggleState(flag, false, ToggleSource.Default).isOverridden)
    }
}
