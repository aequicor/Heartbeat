package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.LauncherUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceSurfaceUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlaceUiTest {
    private val previousLocale = Locale.getDefault()

    @BeforeTest
    fun useEnglish() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `a panel shows its title and close button, and launchers open surfaces`() = runSkikoComposeUiTest(
        size = Size(480f, 600f),
    ) {
        val intents = mutableListOf<PlaceIntent>()
        val state = PlaceState(
            launchers = persistentListOf(LauncherUi("harness:h_1:map", "PR map")),
            panelTitle = "Pull requests",
        )
        setContent { HbTheme(darkTheme = false) { PlaceView(state, persistentMapOf(), { intents += it }, true) } }

        onNodeWithText("Pull requests").assertIsDisplayed()
        onNodeWithContentDescription("Close the panel").performClick()
        onNodeWithText("PR map").performClick()
        assertEquals(listOf(PlaceIntent.ClosePanel, PlaceIntent.Launch("harness:h_1:map")), intents)
    }

    @Test
    fun `an empty slot draws nothing`() = runSkikoComposeUiTest(size = Size(480f, 200f)) {
        setContent {
            HbTheme(darkTheme = false) {
                PlaceView(PlaceState(), persistentMapOf(), {}, isPanel = false, Modifier.testTag("slot"))
            }
        }
        onNodeWithTag("slot").assertDoesNotExist()
    }

    @Test
    fun `a slot has no panel header and its modal closes`() = runSkikoComposeUiTest(size = Size(480f, 600f)) {
        val intents = mutableListOf<PlaceIntent>()
        val state = PlaceState(modal = PlaceSurfaceUi("details", "Details"), panelTitle = "Pull requests")
        setContent { HbTheme(darkTheme = true) { PlaceView(state, persistentMapOf(), { intents += it }, false) } }

        onNodeWithText("Details").assertIsDisplayed()
        onNodeWithText("Close").performClick()
        assertEquals(listOf<PlaceIntent>(PlaceIntent.CloseModal), intents)
        assertEquals(0, onAllNodes(androidx.compose.ui.test.hasText("Pull requests")).fetchSemanticsNodes().size)
    }
}
