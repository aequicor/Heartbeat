package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchMessageUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPhase
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState
import kotlinx.collections.immutable.persistentListOf
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Native capture of the research conversation. Each case replaces the system clipboard for its own duration and
 * restores the previous contents; a foreign owner that refuses republishing only leaves the fixture behind.
 */
@OptIn(ExperimentalTestApi::class)
class ResearchAttachmentPasteUiTest {
    @Test
    fun `a clipboard screenshot reaches the conversation while focus stays outside the composer`() =
        runSkikoComposeUiTest(size = Size(1440f, 900f)) {
            val intents = mutableListOf<ResearchScreenIntent>()
            withClipboard(clipboardImage()) {
                setContent { Conversation(intents::add) }
                waitForIdle()
                onNodeWithTag("research-tab-Sources").performClick()
                onNodeWithTag("research-screen").performKeyInput {
                    withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
                }
                waitForIdle()
                val pasted = intents.filterIsInstance<ResearchScreenIntent.PastedImage>()
                assertEquals(1, pasted.size, "The side panel keeps the paste inside the conversation")
                assertTrue(pasted.single().bytes.isNotEmpty(), "A pasted screenshot arrives as PNG bytes")
            }
        }

    @Test
    fun `a text clipboard keeps the native paste and captures nothing`() =
        runSkikoComposeUiTest(size = Size(1440f, 900f)) {
            val intents = mutableListOf<ResearchScreenIntent>()
            withClipboard(StringSelection("Вопрос исследования")) {
                setContent { Conversation(intents::add) }
                waitForIdle()
                onNodeWithTag("research-tab-Sources").performClick()
                onNodeWithTag("research-screen").performKeyInput {
                    withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
                }
                waitForIdle()
                assertTrue(
                    intents.filterIsInstance<ResearchScreenIntent.PastedImage>().isEmpty(),
                    "Ordinary text paste stays with the focused field",
                )
            }
        }
}

@Composable
private fun Conversation(onIntent: (ResearchScreenIntent) -> Unit) {
    HbTheme(darkTheme = false) { ResearchScreenContent(conversationState(), onIntent, {}) }
}

private fun conversationState(): ResearchScreenState = ResearchScreenState(
    phase = ResearchPhase.Ready,
    messages = persistentListOf(ResearchMessageUi("question", true, "Как устроены нативные контроли?")),
    sessionTitle = "Compose design systems",
    questionTitle = "Как устроены нативные контроли?",
    questionId = "question",
    isEditable = true,
    isFileImportAvailable = true,
)

private fun clipboardImage(): Transferable = ImageTransferable(BufferedImage(24, 16, BufferedImage.TYPE_INT_ARGB))

private val clipboardLog = Log.tag("ResearchTests/Clipboard")

/** Replaces the system clipboard for [block] and restores the previous contents afterwards. */
private inline fun withClipboard(contents: Transferable, block: () -> Unit) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val previous = readClipboard(clipboard)
    clipboard.setContents(contents, null)
    try {
        block()
    } finally {
        restoreClipboard(clipboard, previous)
    }
}

private fun readClipboard(clipboard: Clipboard): Transferable? = try {
    clipboard.getContents(null)
} catch (error: IllegalStateException) {
    clipboardLog.w(error) { "Previous clipboard contents are unavailable" }
    null
}

private fun restoreClipboard(clipboard: Clipboard, previous: Transferable?) {
    if (previous == null) return
    try {
        clipboard.setContents(previous, null)
    } catch (error: IllegalStateException) {
        clipboardLog.w(error) { "Previous clipboard contents were not restored" }
    }
}

private class ImageTransferable(private val image: BufferedImage) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

    override fun getTransferData(flavor: DataFlavor): Any = image
}
