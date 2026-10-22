package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssistOrigin
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.applyComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.composerTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioBrowserSuggestionTest {
    @Test
    fun `browser completion round trips through the screen and inserts without a file attachment`() {
        val command = AiStudioScreenState().hostCommands().single { it.id == "browser" }
        val suggestion = ComposerSuggestion.Command(
            "host:${command.id}",
            command.label,
            command.description,
            ComposerAssistOrigin.Heartbeat,
            command.insert,
        ).toUi().toCompletion()
        val text = "Открой @bro"
        val result = applyComposerSuggestion(text, assertNotNull(composerTrigger(text, text.length)), suggestion)
        assertEquals("Открой @browser ", result.text)
        assertEquals(result.text.length, result.caret)
        assertNull(result.attach)
    }
}
