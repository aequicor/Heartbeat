package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_page
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_paste
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_send
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_mode
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.InputSupportUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioBrowserHostUiTest {
    @Test
    fun `browser host light wide`() = browserHost(width = 1280, isDark = false)

    @Test
    fun `browser host dark wide`() = browserHost(width = 1280, isDark = true)

    @Test
    fun `browser host light compact`() = browserHost(width = 420, isDark = false)

    @Test
    fun `browser host dark compact`() = browserHost(width = 420, isDark = true)

    @Test
    fun `pending import blocks submit and attachment menu actions on every host width`() {
        for (width in listOf(320f, 1280f)) {
            for (isNative in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width, 800f)) {
                    val base = browserState()
                    val state = base.copy(attachmentRequests = persistentMapOf("pending" to base.draftKey(0)))
                    val host = StudioHostCapabilities(
                        isNativeAttachmentInputAvailable = isNative,
                        onAttachPage = { error("pending import must not attach again") },
                    )
                    var add = ""
                    var send = ""
                    var attachmentLabels = emptyList<String>()
                    setContent {
                        add = stringResource(Res.string.composer_add)
                        send = stringResource(Res.string.composer_send)
                        attachmentLabels = listOf(
                            stringResource(Res.string.attachments_page),
                            stringResource(Res.string.attachments_add),
                            stringResource(Res.string.attachments_paste),
                        )
                        HbTheme { AiStudioContent(state, {}, StudioExits(), host = host) }
                    }
                    settleAudit()
                    onNodeWithContentDescription(send).assertIsNotEnabled()
                    onNodeWithContentDescription(add).performClick()
                    attachmentLabels.forEach { onNodeWithText(it).assertDoesNotExist() }
                }
            }
        }
    }

    private fun browserHost(width: Int, isDark: Boolean) = runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
        val requested = mutableListOf<Int>()
        val host = StudioHostCapabilities(
            false,
            false,
            false,
            false,
            onAttachPage = requested::add,
            isOrganismLaunchingAvailable = false,
        )
        val state = browserState()
        var labels = emptyList<String>()
        setContent {
            labels = listOf(
                stringResource(Res.string.composer_add),
                stringResource(Res.string.attachments_page),
                stringResource(Res.string.attachments_add),
                stringResource(Res.string.attachments_paste),
                stringResource(Res.string.organism_mode),
            )
            HbTheme(darkTheme = isDark) { AiStudioContent(state, {}, StudioExits(), host = host) }
        }
        settleAudit()
        onNodeWithTag("attachment-add-0").assertDoesNotExist()
        onNodeWithTag("pane-split").assertDoesNotExist()
        onNodeWithTag("rail-back").assertDoesNotExist()
        onNodeWithTag("rail-toggles").assertDoesNotExist()
        onNodeWithContentDescription(labels[0]).performClick()
        labels.drop(2).forEach { onNodeWithText(it).assertDoesNotExist() }
        onNodeWithText(labels[1]).performClick()
        assertEquals(listOf(0), requested)
        onNodeWithTag("attachment-file").assertHasNoClickAction()
        onNodeWithTag("attachment-remove-file").assertExists()
        saveAudit("browser-$width-$isDark", width, isDark, "page-attachment")
    }
}

private fun browserState(): AiStudioScreenState {
    val base = desktopAuditWorkspace(isEmpty = true)
    return base.copy(
        models = persistentListOf(
            base.models.single().copy(
                inputSupport = InputSupportUi(mediaTypes = persistentListOf("text/markdown", "image/png")),
            ),
        ),
        isAttachmentsEnabled = true,
        draftAttachments = persistentMapOf(
            base.draftKey(0) to persistentListOf(AttachmentUi("file", "Page.md", "text/markdown", 100)),
        ),
    )
}
