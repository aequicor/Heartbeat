package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals

class HbComposerSuggestionsTest {
    @Test
    fun `no suggestions leave every key to the editor`() {
        assertEquals(ComposerSuggestionKeys.Idle, resolve(Key.Enter, hasSuggestions = false))
        assertEquals(ComposerSuggestionKeys.Idle, resolve(Key.Escape, hasSuggestions = false))
    }

    @Test
    fun `arrows enter and tab navigate accept and dismiss`() {
        assertEquals(ComposerSuggestionKeys.Navigate, resolve(Key.DirectionUp, hasSuggestions = true))
        assertEquals(ComposerSuggestionKeys.Navigate, resolve(Key.DirectionDown, hasSuggestions = true))
        assertEquals(ComposerSuggestionKeys.Accept, resolve(Key.Enter, hasSuggestions = true))
        assertEquals(ComposerSuggestionKeys.Accept, resolve(Key.NumPadEnter, hasSuggestions = true))
        assertEquals(ComposerSuggestionKeys.Accept, resolve(Key.Tab, hasSuggestions = true))
        assertEquals(ComposerSuggestionKeys.Dismiss, resolve(Key.Escape, hasSuggestions = true))
    }

    @Test
    fun `modified keys keep the editor behaviour`() {
        assertEquals(ComposerSuggestionKeys.Idle, resolve(Key.Enter, shift = true, hasSuggestions = true))
        assertEquals(ComposerSuggestionKeys.Idle, resolve(Key.Enter, ctrl = true, hasSuggestions = true))
        assertEquals(ComposerSuggestionKeys.Idle, resolve(Key.DirectionUp, ctrl = true, hasSuggestions = true))
        assertEquals(ComposerSuggestionKeys.Idle, resolve(Key.A, hasSuggestions = true))
    }
}

private fun resolve(key: Key, shift: Boolean = false, ctrl: Boolean = false, hasSuggestions: Boolean) =
    composerSuggestionKey(key, shift, ctrl, isMetaPressed = false, hasSuggestions = hasSuggestions)
