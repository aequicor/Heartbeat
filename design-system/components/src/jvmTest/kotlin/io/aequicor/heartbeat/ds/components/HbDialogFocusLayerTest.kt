package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class HbDialogFocusLayerTest {
    @Test
    fun `only the active modal is exposed to accessibility and closing restores the background`() =
        runSkikoComposeUiTest {
            var isActive by mutableStateOf(false)
            val dialog = SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)
            val hidden = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            setContent {
                HbDialogFocusLayer(isActive, background = { FocusTarget("background") }) {
                    FocusTarget("modal")
                }
            }
            onNodeWithTag("background", useUnmergedTree = true).assert(!hidden)
            onAllNodes(dialog, useUnmergedTree = true).assertCountEquals(0)
            runOnIdle { isActive = true }
            onNodeWithTag("background", useUnmergedTree = true).assert(hidden)
            onNodeWithTag("modal", useUnmergedTree = true).assert(hasAnyAncestor(dialog) and !hidden)
            onAllNodes(dialog, useUnmergedTree = true).assertCountEquals(1)
            runOnIdle { isActive = false }
            onNodeWithTag("background", useUnmergedTree = true).assert(!hidden)
            onAllNodes(dialog, useUnmergedTree = true).assertCountEquals(0)
        }

    @Test
    fun `closing the modal restores its opener instead of the first background control`() = runSkikoComposeUiTest {
        var isActive by mutableStateOf(false)
        val opener = FocusRequester()
        val modal = FocusRequester()
        setContent {
            HbDialogFocusLayer(
                isActive,
                background = {
                    Column {
                        FocusTarget("first")
                        FocusTarget("opener", requester = opener)
                    }
                },
            ) {
                LaunchedEffect(Unit) { modal.requestFocus() }
                FocusTarget("modal", requester = modal)
            }
        }
        runOnIdle { opener.requestFocus() }
        onNodeWithTag("opener").assertIsFocused()
        runOnIdle { isActive = true }
        onNodeWithTag("modal").assertIsFocused()
        onNodeWithTag("opener").assertIsNotFocused()
        runOnIdle { isActive = false }
        onNodeWithTag("opener").assertIsFocused()
        onNodeWithTag("first").assertIsNotFocused()
    }

    @Test
    fun `a removed opener falls back to an existing background control`() = runSkikoComposeUiTest {
        var isActive by mutableStateOf(false)
        var hasOpener by mutableStateOf(true)
        val opener = FocusRequester()
        val modal = FocusRequester()
        setContent {
            HbDialogFocusLayer(
                isActive,
                background = {
                    Column {
                        FocusTarget("remaining")
                        if (hasOpener) FocusTarget("opener", requester = opener)
                    }
                },
            ) {
                LaunchedEffect(Unit) { modal.requestFocus() }
                FocusTarget("modal", requester = modal)
            }
        }
        runOnIdle { opener.requestFocus() }
        runOnIdle { isActive = true }
        onNodeWithTag("modal").assertIsFocused()
        runOnIdle { hasOpener = false }
        onNodeWithTag("modal").assertIsFocused()
        runOnIdle { isActive = false }
        onNodeWithTag("remaining").assertIsFocused()
    }
}

@Composable
private fun FocusTarget(tag: String, modifier: Modifier = Modifier, requester: FocusRequester? = null) {
    Box(
        modifier.size(40.dp).testTag(tag)
            .then(if (requester == null) Modifier else Modifier.focusRequester(requester))
            .focusable(),
    )
}
