package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HbCopyButtonUiTest {
    private val motion = HbMotion(isReducedMotion = true)

    @Test
    fun `copied text is confirmed for a while and then the button is ready again`() = runSkikoComposeUiTest {
        val clipboard = CopyTestClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                HbTheme(motion = motion) { HbCopyButton("claude auth login", "Copy command", "Copied", "Copy failed") }
            }
        }
        val button = onNodeWithContentDescription("Copy command")
        mainClock.autoAdvance = false

        button.performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        runOnIdle { assertEquals("claude auth login", clipboard.text()) }
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Copied"))
        mainClock.advanceTimeBy(motion.copyFeedbackMillis.toLong() + 100)
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Copy command"))
    }

    @Test
    fun `a refused clipboard is reported and blank text cannot be copied`() = runSkikoComposeUiTest {
        val clipboard = CopyTestClipboard(isRefusing = true)
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                HbTheme(motion = motion) {
                    HbRow {
                        HbCopyButton("code", "Copy code", "Copied", "Copy failed")
                        HbCopyButton(" ", "Copy nothing", "Copied", "Copy failed")
                    }
                }
            }
        }
        val button = onNodeWithContentDescription("Copy code")
        mainClock.autoAdvance = false

        button.performClick()
        repeat(FEEDBACK_FRAMES) { mainClock.advanceTimeByFrame() }

        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Copy failed"))
        onNodeWithContentDescription("Copy nothing").assertIsNotEnabled()
    }
}

private const val FEEDBACK_FRAMES = 4

private class CopyTestClipboard(private val isRefusing: Boolean = false) : Clipboard {
    private var entry: ClipEntry? = null

    override suspend fun getClipEntry(): ClipEntry? = entry

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        check(!isRefusing) { "Clipboard unavailable" }
        entry = clipEntry
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun text(): String? = entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
}
