package io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.ToggleSource
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.feature.togglespanel.api.ToggleOperation
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelState
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TogglePresentationTest {
    private val flag = FeatureToggle.Flag("test.flag", "Flag")
    private val choice = FeatureToggle.Choice("test.mode", "Mode", listOf("One", "Two"))
    private val flagState = ToggleState(flag, true, ToggleSource.LocalOverride)
    private val choiceState = ToggleState(choice, "Two", ToggleSource.Default)
    private val active = TogglesPanelState.Active(listOf(flagState, choiceState))

    @Test
    fun `rows expose values and metadata without domain toggle instances`() {
        assertEquals(ToggleRowUi("test.flag", "test", "Flag", true, ToggleControlUi.Flag(true)), flagState.toUi())
        assertEquals(ToggleControlUi.Choice("Two", persistentListOf("One", "Two")), choiceState.toUi().control)
    }

    @Test
    fun `key based edits resolve against the current domain definitions`() {
        assertEquals(
            ToggleOperation.SetFlag(flag, false),
            TogglesPanelScreenIntent.SetFlag(flag.key, false).toOperation(active),
        )
        assertEquals(
            ToggleOperation.SetChoice(choice, "One"),
            TogglesPanelScreenIntent.SetChoice(choice.key, "One").toOperation(active),
        )
        assertEquals(ToggleOperation.Reset(flag), TogglesPanelScreenIntent.Reset(flag.key).toOperation(active))
        assertEquals(ToggleOperation.ResetAll, TogglesPanelScreenIntent.ResetAll.toOperation(active))
    }

    @Test
    fun `stale or mistyped edits never construct an operation for another flag`() {
        assertNull(TogglesPanelScreenIntent.SetFlag("missing", true).toOperation(active))
        assertNull(TogglesPanelScreenIntent.SetFlag(choice.key, true).toOperation(active))
        assertNull(TogglesPanelScreenIntent.SetChoice(flag.key, "One").toOperation(active))
        assertNull(TogglesPanelScreenIntent.Reset(flag.key).toOperation(TogglesPanelState.LoadError))
    }
}
