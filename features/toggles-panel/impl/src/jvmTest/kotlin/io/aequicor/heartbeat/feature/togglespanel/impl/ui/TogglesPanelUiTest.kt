package io.aequicor.heartbeat.feature.togglespanel.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.ToggleSource
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelScreenIntent
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.TogglesPanelScreenState
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store.toUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TogglesPanelUiTest {
    private val flag = FeatureToggle.Flag("alpha.enabled", "Cinematic light")
    private val choice = FeatureToggle.Choice("beta.mode", "Engine mode", listOf("One", "Two"))
    private val loaded = TogglesPanelScreenState(
        rows = persistentListOf(
            ToggleState(flag, true, ToggleSource.LocalOverride).toUi(),
            ToggleState(choice, "One", ToggleSource.Default).toUi(),
        ),
        isLoading = false,
    )

    @Test
    fun `search matches key and description while retaining owner groups`() = runSkikoComposeUiTest {
        var state by mutableStateOf(loaded)
        setContent {
            HbTheme {
                TogglesPanelContent(state, {
                    if (it is TogglesPanelScreenIntent.Search) state = state.copy(query = it.query)
                }, {})
            }
        }
        onNodeWithTag("flags-search").performTextInput("CINEMATIC")
        onNodeWithText("alpha").assertExists()
        onNodeWithText("beta").assertDoesNotExist()
        onNodeWithTag("flag:alpha.enabled").assertIsOn()
        onNodeWithTag("flags-search").performTextReplacement("beta.mode")
        onNodeWithText("alpha").assertDoesNotExist()
        onNodeWithText("beta").assertExists()
        onNodeWithTag("flags-list").performScrollToNode(hasTestTag("choice:beta.mode:Two"))
        onNodeWithTag("choice:beta.mode:One").assertIsSelected()
    }

    @Test
    fun `flag choice and reset emit typed operations and isSaving disables mutations`() = runSkikoComposeUiTest(
        size = Size(1000f, 1100f),
    ) {
        var state by mutableStateOf(loaded)
        val events = mutableListOf<TogglesPanelScreenIntent>()
        setContent { HbTheme { TogglesPanelContent(state, events::add, {}) } }
        onNodeWithTag("flag:alpha.enabled").performClick()
        assertEquals(TogglesPanelScreenIntent.SetFlag(flag.key, false), events.last())
        onNodeWithTag("choice:beta.mode:Two").performClick()
        assertEquals(TogglesPanelScreenIntent.SetChoice(choice.key, "Two"), events.last())
        onNodeWithTag("reset:alpha.enabled").performClick()
        assertEquals(TogglesPanelScreenIntent.Reset(flag.key), events.last())
        onNodeWithTag("flags-reset-all").performClick()
        assertEquals(TogglesPanelScreenIntent.ResetAll, events.last())
        runOnIdle { state = loaded.copy(isSaving = true) }
        onNodeWithTag("flag:alpha.enabled").assertIsNotEnabled()
        onNodeWithTag("choice:beta.mode:Two").assertIsNotEnabled()
        onNodeWithTag("reset:alpha.enabled").assertIsNotEnabled()
        onNodeWithTag("flags-reset-all").assertIsNotEnabled()
        onNodeWithTag("flags-search").assertIsEnabled()
    }

    @Test
    fun `read and write errors have distinct retry actions`() = runSkikoComposeUiTest {
        var state by mutableStateOf(TogglesPanelScreenState(isLoading = false, hasLoadError = true))
        val events = mutableListOf<TogglesPanelScreenIntent>()
        setContent { HbTheme { TogglesPanelContent(state, events::add, {}) } }
        onNodeWithTag("flags-retry").performClick()
        assertEquals(TogglesPanelScreenIntent.RetryLoad, events.last())
        runOnIdle { state = loaded.copy(hasWriteError = true) }
        onNodeWithTag("flags-retry").performClick()
        assertEquals(TogglesPanelScreenIntent.RetryWrite, events.last())
    }

    @Test
    fun `compact panel with large text keeps reset and choices reachable`() = runSkikoComposeUiTest(
        size = Size(320f, 640f),
    ) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                HbTheme { TogglesPanelContent(loaded, {}, {}) }
            }
        }
        onNodeWithTag("flags-list").performScrollToNode(hasTestTag("reset:alpha.enabled"))
        onNodeWithTag("reset:alpha.enabled").assertIsDisplayed().assertIsEnabled()
        onNodeWithTag("flags-list").performScrollToNode(hasTestTag("choice:beta.mode:Two"))
        onNodeWithTag("choice:beta.mode:Two").assertIsDisplayed().performClick()
    }
}
