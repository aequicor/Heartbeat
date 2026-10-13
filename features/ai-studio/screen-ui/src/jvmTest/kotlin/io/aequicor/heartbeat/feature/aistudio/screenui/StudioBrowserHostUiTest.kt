package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.InputSupportUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
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

    private fun browserHost(width: Int, isDark: Boolean) = runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
        val requested = mutableListOf<Int>()
        val host = StudioHostCapabilities(false, false, false, false, onAttachPage = requested::add)
        val state = browserState()
        setContent { HbTheme(darkTheme = isDark) { AiStudioContent(state, {}, StudioExits(), host = host) } }
        settleAudit()
        onNodeWithTag("attachment-add-0").assertDoesNotExist()
        onNodeWithTag("pane-split").assertDoesNotExist()
        onNodeWithTag("rail-back").assertDoesNotExist()
        onNodeWithTag("rail-toggles").assertDoesNotExist()
        onNodeWithTag("attachment-page-0").performClick()
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
                inputSupport = InputSupportUi(mediaTypes = persistentListOf("text/markdown")),
            ),
        ),
        isAttachmentsEnabled = true,
        draftAttachments = persistentMapOf(
            base.draftKey(0) to persistentListOf(AttachmentUi("file", "Page.md", "text/markdown", 100)),
        ),
    )
}
