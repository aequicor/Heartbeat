package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.PlacementUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.WriteNoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.LibraryIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.LibraryPhase
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.LibraryState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.OwnerGroupUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.SurfaceRowUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LibraryUiTest {
    private val previousLocale = Locale.getDefault()
    private val map = SurfaceRowUi("harness:h_1:map", "PR map", "Dependencies", PlacementUi.Panel, isEnabled = true)
    private val notes = SurfaceRowUi("harness:h_2:notes", "Notes", "", PlacementUi.Modal, isEnabled = false)
    private val state = LibraryState(
        phase = LibraryPhase.Ready,
        groups = persistentListOf(
            OwnerGroupUi("harness:h_1", "PR review", isKnown = true, isEnabled = true, persistentListOf(map)),
            OwnerGroupUi("harness:h_2", "Release", isKnown = true, isEnabled = false, persistentListOf(notes)),
        ),
        pending = persistentSetOf(notes.id),
    )

    @BeforeTest
    fun useEnglish() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `surfaces show under their owners and switch or open on input`() = runSkikoComposeUiTest(
        size = Size(560f, 720f),
    ) {
        val intents = mutableListOf<LibraryIntent>()
        setContent { HbTheme(darkTheme = false) { LibraryContent(state, { intents += it }, onBack = null) } }

        onNodeWithText("PR review").assertIsDisplayed()
        onNodeWithText("Side panel · Dependencies").assertIsDisplayed()
        onNodeWithText("The owner is turned off; its interfaces are hidden").assertIsDisplayed()
        onNodeWithContentDescription("Show “Notes”").assertIsNotEnabled()
        onNodeWithContentDescription("Show “PR map”").performClick()
        onNodeWithText("PR map").performClick()
        assertEquals(
            listOf(LibraryIntent.SetEnabled(map.id, isEnabled = false), LibraryIntent.Open(map.id)),
            intents,
        )
    }

    @Test
    fun `a standalone library has a back button and shows why a change failed`() = runSkikoComposeUiTest(
        size = Size(360f, 640f),
    ) {
        var isBack = false
        val intents = mutableListOf<LibraryIntent>()
        setContent {
            HbTheme(darkTheme = true) {
                LibraryContent(state.copy(notice = WriteNoticeUi.Busy), { intents += it }, onBack = { isBack = true })
            }
        }

        onNodeWithText("Another change is being saved; try again").assertIsDisplayed()
        onNodeWithText("Dismiss").performClick()
        onNodeWithTag("service-ui-library-back").performClick()
        assertEquals(listOf<LibraryIntent>(LibraryIntent.DismissNotice), intents)
        assertEquals(true, isBack)
    }

    @Test
    fun `an empty, failed or disabled library explains itself`() = runSkikoComposeUiTest(size = Size(480f, 480f)) {
        var phase by mutableStateOf(LibraryPhase.Ready)
        val intents = mutableListOf<LibraryIntent>()
        setContent { HbTheme(darkTheme = false) { LibraryContent(LibraryState(phase), { intents += it }, null) } }

        onNodeWithText("No interfaces yet").assertIsDisplayed()
        phase = LibraryPhase.Disabled
        onNodeWithText("Agent interfaces are turned off").assertIsDisplayed()
        phase = LibraryPhase.Failed
        onNodeWithText("Retry").performClick()
        assertEquals(listOf<LibraryIntent>(LibraryIntent.Reload), intents)
    }
}
