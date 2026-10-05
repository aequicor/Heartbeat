package io.aequicor.heartbeat.ds.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.platform.PlatformLocalization
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import io.aequicor.heartbeat.ds.adaptive.AdaptiveTextField
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class HbFluentTextContextMenuTest {
    private data class Snapshot(
        val textMenu: TextContextMenu,
        val representation: Any,
        val localization: PlatformLocalization,
    )

    @Test
    fun `fluent text field opens a fluent styled text context menu on right click`() = runComposeUiTest {
        var snapshot: Snapshot? = null
        setContent {
            HbTheme(darkTheme = false, platformUi = PlatformUi.Fluent, visualStyle = HbVisualStyle.Platform) {
                var value by remember { mutableStateOf(FIELD_TEXT) }
                AdaptiveTextField(value, { value = it }, Modifier.testTag(FIELD_TAG))
                val current = Snapshot(
                    LocalTextContextMenu.current,
                    LocalContextMenuRepresentation.current,
                    LocalLocalization.current,
                )
                SideEffect { snapshot = current }
            }
        }
        // A full selection keeps the enabled actions identical on every host: macOS selects the word under
        // the pointer on right click only when the pointer is outside the current selection.
        onNodeWithTag(FIELD_TAG).performTextInputSelection(TextRange(0, FIELD_TEXT.length))
        onNodeWithTag(FIELD_TAG).performMouseInput { rightClick(center) }

        val value = checkNotNull(snapshot)
        onNodeWithText(value.localization.cut).assertExists()
        onNodeWithText(value.localization.copy).assertExists()
        // The Fluent representation ignores ContextMenuItem.enabled, so disabled actions must not be offered.
        onNodeWithText(value.localization.selectAll).assertDoesNotExist()
        assertSame(TextContextMenu.HideDisabledMenuItems, value.textMenu)
        assertTrue(value.representation.javaClass.name.startsWith(FLUENT_PACKAGE), value.representation.toString())
    }

    private companion object {
        const val FIELD_TAG = "fluent-text-field"
        const val FIELD_TEXT = "Heartbeat"
        const val FLUENT_PACKAGE = "io.github.composefluent."
    }
}
