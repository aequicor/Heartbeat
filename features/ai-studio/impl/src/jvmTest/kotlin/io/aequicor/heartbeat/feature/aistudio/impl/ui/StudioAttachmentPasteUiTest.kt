package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.InputSupportUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.NativeAttachmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
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
 * Native capture of the studio screen. Each case replaces the system clipboard for its own duration and restores the
 * previous contents; a foreign owner that refuses republishing only leaves the fixture behind.
 */
@OptIn(ExperimentalTestApi::class)
class StudioAttachmentPasteUiTest {
    @Test
    fun `a clipboard screenshot follows keyboard focus into another pane without a click`() =
        runSkikoComposeUiTest(size = Size(1800f, 900f)) {
            val intents = mutableListOf<AiStudioScreenIntent>()
            var state by mutableStateOf(attachmentState().copy(panes = persistentListOf(PaneUi(0), PaneUi(1))))
            withClipboard(clipboardImage()) {
                setContent {
                    HbTheme(darkTheme = false) {
                        AiStudioContent(state, { intent ->
                            intents.add(intent)
                            if (intent is AiStudioScreenIntent.FocusPane) {
                                state = state.copy(focusedPaneId = intent.paneId)
                            }
                        }, exits)
                    }
                }
                settleAudit()
                val editor = onNode(hasAnyAncestor(hasTestTag("composer-1")) and hasSetTextAction())
                editor.performSemanticsAction(SemanticsActions.RequestFocus)
                settleAudit()
                editor.assertIsFocused().performPasteShortcut()
                settleAudit()
                assertEquals(listOf(1), intents.imports().map { it.paneId }, "The paste follows keyboard focus")
            }
        }

    @Test
    fun `a clipboard screenshot reaches the focused pane while focus rests on the workspace`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val intents = mutableListOf<AiStudioScreenIntent>()
            withClipboard(clipboardImage()) {
                setContent { HbTheme(darkTheme = false) { AiStudioContent(attachmentState(), intents::add, exits) } }
                settleAudit()
                onNodeWithTag("studio-workspace").performPasteShortcut()
                settleAudit()
                val imports = intents.imports()
                assertEquals(listOf(0), imports.map { it.paneId }, "The paste belongs to the focused pane")
                val image = imports.single().inputs.single() as NativeAttachmentUi.Image
                assertTrue(image.bytes.isNotEmpty(), "A pasted screenshot arrives as PNG bytes")
            }
        }

    @Test
    fun `a text clipboard keeps the native editor paste and imports nothing`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val intents = mutableListOf<AiStudioScreenIntent>()
            withClipboard(StringSelection("Черновик исследования")) {
                setContent { HbTheme(darkTheme = false) { AiStudioContent(attachmentState(), intents::add, exits) } }
                settleAudit()
                onNodeWithTag("studio-workspace").performPasteShortcut()
                settleAudit()
                assertTrue(intents.imports().isEmpty(), "Ordinary text paste stays with the editor")
            }
        }

    @Test
    fun `a model without confirmed input support captures nothing`() = runSkikoComposeUiTest(size = Size(1280f, 800f)) {
        val intents = mutableListOf<AiStudioScreenIntent>()
        val state = desktopAuditWorkspace(isEmpty = true).copy(isAttachmentsEnabled = true)
        withClipboard(clipboardImage()) {
            setContent { HbTheme(darkTheme = false) { AiStudioContent(state, intents::add, exits) } }
            settleAudit()
            onNodeWithTag("studio-workspace").performPasteShortcut()
            settleAudit()
            assertTrue(intents.imports().isEmpty(), "An unconfirmed route must not accept a screenshot")
        }
    }
}

private val exits = StudioExits(
    onBack = {},
    onOpenToggles = {},
    onOpenProfileSettings = {},
    onOpenConnections = {},
    onOpenResearch = {},
    onOpenSettings = {},
)

/** The audit workspace with one route that confirms image and document input. */
private fun attachmentState(): AiStudioScreenState {
    val base = desktopAuditWorkspace(isEmpty = true)
    return base.copy(
        models = persistentListOf(
            base.models.single().copy(
                inputSupport = InputSupportUi(
                    mediaTypes = persistentListOf("image/png", "text/markdown"),
                    imageMediaTypes = persistentListOf("image/png"),
                ),
            ),
        ),
        isAttachmentsEnabled = true,
    )
}

private fun List<AiStudioScreenIntent>.imports(): List<AiStudioScreenIntent.ImportAttachments> =
    filterIsInstance<AiStudioScreenIntent.ImportAttachments>()

@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteraction.performPasteShortcut() =
    performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }

private fun clipboardImage(): Transferable = ImageTransferable(BufferedImage(24, 16, BufferedImage.TYPE_INT_ARGB))

private val clipboardLog = Log.tag("StudioTests/Clipboard")

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
