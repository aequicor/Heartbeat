package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AttachmentPreviewUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.InputSupportUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_save
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_send
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioAttachmentsUiTest {
    private val file = AttachmentUi("doc", "Заметки об исследовании.md", "text/markdown", 2048)
    private val pane = PaneUi(0)

    @Test
    fun `attachment-only composer sends supported files`() = runSkikoComposeUiTest(size = Size(800f, 500f)) {
        val events = mutableListOf<AiStudioScreenIntent>()
        val support = InputSupportUi(mediaTypes = persistentListOf(file.mediaType))
        val state = attachmentState(support)
        var send = ""
        setContent {
            send = stringResource(Res.string.composer_send)
            AttachmentComposer(state, events::add)
        }
        onNodeWithContentDescription(send).assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.Submit(0)), events.userActions()) }
    }

    @Test
    fun `changing to a model without resource support retains the attachment and disables send`() =
        runSkikoComposeUiTest(size = Size(800f, 500f)) {
            var send = ""
            setContent {
                send = stringResource(Res.string.composer_send)
                AttachmentComposer(attachmentState(InputSupportUi()), {})
            }
            onNodeWithTag("attachment-doc").assertIsDisplayed()
            onNodeWithContentDescription(send).assertIsNotEnabled()
        }

    @Test
    fun `saved file offers preview and native export`() = runSkikoComposeUiTest(size = Size(800f, 500f)) {
        val events = mutableListOf<AiStudioScreenIntent>()
        var save = ""
        setContent {
            save = stringResource(Res.string.attachments_save)
            HbTheme(darkTheme = false) {
                StudioAttachments(persistentListOf(file), events::add)
            }
        }
        onNodeWithTag("attachment-doc").performClick()
        onNodeWithContentDescription(save).performClick()
        runOnIdle {
            assertEquals(
                listOf<AiStudioScreenIntent>(
                    AiStudioScreenIntent.OpenAttachment("doc"),
                    AiStudioScreenIntent.ExportAttachment("doc"),
                ),
                events.userActions(),
            )
        }
    }

    @Test
    fun `attachment composer renders at compact width in light and dark themes`() {
        val folder = File("build/screenshots/attachments").apply { mkdirs() }
        listOf(false, true).forEach { dark ->
            runSkikoComposeUiTest(size = Size(400f, 400f)) {
                setContent {
                    AttachmentComposer(
                        attachmentState(InputSupportUi(mediaTypes = persistentListOf(file.mediaType))),
                        {},
                        dark,
                    )
                }
                onNodeWithTag("attachment-doc").assertIsDisplayed()
                check(ImageIO.write(captureToImage().toAwtImage(), "png", File(folder, "composer-$dark.png")))
            }
        }
    }

    @Test
    fun `ten mobile attachments scroll while editor send and removal remain reachable`() {
        val folder = File("build/screenshots/attachments").apply { mkdirs() }
        listOf(false, true).forEach { dark ->
            runSkikoComposeUiTest(size = Size(420f, 640f)) {
                val events = mutableListOf<AiStudioScreenIntent>()
                var send = ""
                val files = (0..9).map { file.copy(id = "doc-$it", name = "Исследовательские заметки $it.md") }
                val state = attachmentState(InputSupportUi(mediaTypes = persistentListOf(file.mediaType))).copy(
                    draftAttachments = persistentMapOf("pane:0" to files.toImmutableList()),
                )
                setContent {
                    send = stringResource(Res.string.composer_send)
                    AttachmentComposer(state, events::add, dark, isMobile = true)
                }
                onNodeWithTag("draft-attachments-0").assertHeightIsEqualTo(HbDimensions.Mobile.composerMaxHeight)
                onNodeWithTag("composer-0").assertIsDisplayed()
                onNodeWithContentDescription(send).assertIsDisplayed().assertIsEnabled()
                check(ImageIO.write(captureToImage().toAwtImage(), "png", File(folder, "mobile-ten-$dark.png")))
                onNodeWithTag("draft-attachments-0").performScrollToIndex(9)
                onNodeWithTag("attachment-remove-doc-9").assertIsDisplayed().performClick()
                runOnIdle {
                    assertEquals(
                        listOf<AiStudioScreenIntent>(AiStudioScreenIntent.RemoveAttachment(0, "doc-9")),
                        events.userActions(),
                    )
                }
            }
        }
    }

    @Test
    fun `image and document thumbnails appear in compact drafts and saved history in both themes`() {
        val folder = File("build/screenshots/attachments").apply { mkdirs() }
        val photo = AttachmentUi("photo", "Схема исследования.png", "image/png", 4096)
        val pdf = AttachmentUi("pdf", "Документ.pdf", "application/pdf", 10240)
        val previews = persistentMapOf(
            "photo" to AttachmentPreviewUi(imageBytes = attachmentImageFixture()),
            "doc" to AttachmentPreviewUi(documentSnippet = "План исследования: сравнить источники и проверить выводы."),
        )
        val files = persistentListOf(photo, file)
        val state = attachmentState(InputSupportUi(mediaTypes = persistentListOf("image/png", file.mediaType))).copy(
            draftAttachments = persistentMapOf("pane:0" to files),
            attachmentPreviews = previews,
        )
        listOf(false, true).forEach { dark ->
            runSkikoComposeUiTest(size = Size(420f, 640f)) {
                setContent { AttachmentComposer(state, {}, dark, isMobile = true) }
                onNodeWithContentDescription(photo.name).assertIsDisplayed()
                onNodeWithTag("attachment-doc").assertIsDisplayed()
                check(ImageIO.write(captureToImage().toAwtImage(), "png", File(folder, "thumbnails-draft-$dark.png")))
            }
            runSkikoComposeUiTest(size = Size(420f, 640f)) {
                setContent {
                    HbTheme(darkTheme = dark, dimensions = HbDimensions.Mobile) {
                        HbColumn(
                            Modifier.fillMaxSize().background(HbTheme.colors.background).padding(HbTheme.spacing.m),
                        ) {
                            StudioAttachments(persistentListOf(photo, file, pdf), {}, previews = previews)
                        }
                    }
                }
                onNodeWithContentDescription(photo.name).assertIsDisplayed()
                onNodeWithTag("attachment-pdf").assertIsDisplayed()
                check(ImageIO.write(captureToImage().toAwtImage(), "png", File(folder, "thumbnails-history-$dark.png")))
            }
        }
    }

    private fun attachmentState(support: InputSupportUi): AiStudioScreenState {
        val initial = AiStudioScreenState(panes = persistentListOf(pane))
        return initial.copy(
            models = persistentListOf(ModelUi("model", "Model", inputSupport = support)),
            settings = initial.settings.copy(modelId = "model"),
            draftAttachments = persistentMapOf("pane:0" to persistentListOf(file)),
            isAttachmentsEnabled = true,
        )
    }
}

@Composable
private fun AttachmentComposer(
    state: AiStudioScreenState,
    onIntent: (AiStudioScreenIntent) -> Unit,
    darkTheme: Boolean = false,
    isMobile: Boolean = false,
) {
    val content = state.paneContent(state.panes.single())
    HbTheme(darkTheme = darkTheme, dimensions = if (isMobile) HbDimensions.Mobile else HbDimensions.Desktop) {
        HbColumn(Modifier.fillMaxSize().background(HbTheme.colors.background).padding(HbTheme.spacing.m)) {
            StudioAttachments(
                content.attachments,
                onIntent,
                paneId = 0,
                support = content.models.single().inputSupport,
                previews = content.attachmentPreviews,
            )
            StudioComposer(content, onIntent, isCompact = isMobile)
        }
    }
}

private fun List<AiStudioScreenIntent>.userActions() = filterNot {
    it is AiStudioScreenIntent.AttachmentPreviewVisible
}
