package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_send
import io.aequicor.heartbeat.feature.aistudio.impl.resources.composer_stop
import io.aequicor.heartbeat.feature.aistudio.impl.resources.queue_close
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentPreviewUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ChatQueueUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.QueueActionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.QueueNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.QueuedMessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withDraft
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

@OptIn(ExperimentalTestApi::class)
class StudioQueueUiTest {
    private val pane = PaneUi(0, sessionId = "chat")
    private val state = AiStudioScreenState(
        panes = persistentListOf(pane),
        models = StudioModelOptions,
        isMessageQueueEnabled = true,
        running = kotlinx.collections.immutable.persistentSetOf("chat"),
        sessions = persistentListOf(
            SessionUi("chat", "Queue", null, Instant.fromEpochMilliseconds(0), isRunning = true),
        ),
        messageQueues = persistentMapOf(
            "chat" to ChatQueueUi(
                (1..5).map {
                    QueuedMessageUi("$it", "Сообщение $it: проверь стрелку и сохрани остальные изменения")
                }.toImmutableList(),
            ),
        ),
    )

    @Test
    fun `working composer queues Enter and steers Ctrl Enter while empty input retains Stop`() =
        runSkikoComposeUiTest(size = Size(1000f, 700f)) {
            var snapshot by mutableStateOf(state)
            val events = mutableListOf<StudioIntent>()
            var send = ""
            var stop = ""
            setContent {
                send = stringResource(Res.string.composer_send)
                stop = stringResource(Res.string.composer_stop)
                HbTheme(dimensions = HbDimensions.Desktop) {
                    StudioComposer(snapshot.paneContent(pane), events::add, isCompact = false)
                }
            }
            onNodeWithContentDescription(stop).performClick()
            runOnIdle { snapshot = snapshot.withDraft(0, "Correction") }
            onNodeWithContentDescription(send).assertIsDisplayed()
            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
            onNode(
                hasSetTextAction(),
            ).performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.Enter)
                keyUp(Key.CtrlLeft)
            }
            runOnIdle {
                assertEquals(
                    listOf<StudioIntent>(
                        StudioIntent.Stop("chat"),
                        StudioIntent.Submit(0),
                        StudioIntent.Submit(0, isCorrection = true),
                    ),
                    events.filterNot { it is StudioIntent.Suggestions },
                )
            }
        }

    @Test
    fun `queue shows three rows and dismissing snackbar changes no message`() =
        runSkikoComposeUiTest(size = Size(1000f, 700f)) {
            val events = mutableListOf<StudioIntent>()
            var close = ""
            setContent {
                close = stringResource(Res.string.queue_close)
                HbTheme(dimensions = HbDimensions.Desktop) {
                    val content = state.copy(queueNotices = persistentMapOf("chat" to QueueNoticeUi("notice", false)))
                        .paneContent(pane)
                    Box {
                        StudioMessageQueue(content, events::add)
                        QueueSnackbar(content, events::add)
                    }
                }
            }
            onNodeWithTag("queued-1").assertIsDisplayed()
            onNodeWithTag("queued-3").assertIsDisplayed()
            onNodeWithTag("queued-4").assertDoesNotExist()
            onNodeWithContentDescription(close).performClick()
            runOnIdle {
                assertEquals(
                    listOf<StudioIntent>(StudioIntent.ManageQueue("chat", QueueActionUi.DismissNotice, "notice")),
                    events,
                )
            }
        }

    @Test
    fun `real pane snackbar close receives pointer input above the composer`() =
        runSkikoComposeUiTest(size = Size(390f, 844f)) {
            val events = mutableListOf<StudioIntent>()
            var close = ""
            setContent {
                close = stringResource(Res.string.queue_close)
                HbTheme(dimensions = HbDimensions.Mobile) {
                    val content = state.copy(
                        queueNotices = persistentMapOf("chat" to QueueNoticeUi("notice", false, true)),
                    )
                        .paneContent(pane)
                    StudioPaneView(
                        content,
                        events::add,
                        PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = true),
                        Modifier.fillMaxSize(),
                    )
                }
            }
            onNodeWithContentDescription(close).assertIsDisplayed().performMouseInput { click() }
            runOnIdle {
                kotlin.test.assertTrue(
                    events.contains(StudioIntent.ManageQueue("chat", QueueActionUi.DismissNotice, "notice")),
                )
            }
            val directory = File("build/reports/queue").apply { mkdirs() }
            check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "phone-snackbar.png")))
        }

    @Test
    fun `render bounded queue at phone tablet and desktop widths in both themes`() {
        for (width in listOf(390, 834, 1280)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 500f)) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(
                                darkTheme = dark,
                                dimensions = if (width < 1200) HbDimensions.Mobile else HbDimensions.Desktop,
                            ) {
                                Box(
                                    Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop),
                                    contentAlignment = Alignment.BottomCenter,
                                ) {
                                    StudioComposer(
                                        previewState().withDraft(0, "Добавь проверку очереди").paneContent(pane),
                                        {},
                                        false,
                                        Modifier.width(minOf(width, 760).dp),
                                    )
                                }
                            }
                        }
                    }
                    onNodeWithTag("queued-3").assertIsDisplayed()
                    val directory = File("build/reports/queue").apply { mkdirs() }
                    check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$width-$dark.png")))
                }
            }
        }
    }
    private fun previewState(): AiStudioScreenState {
        val file = AttachmentUi("image", "screenshot.png", "image/png", 100)
        val queue = state.messageQueues.getValue("chat")
        val messages = queue.messages.mapIndexed { index, row ->
            if (index == 0) row.copy(attachments = persistentListOf(file)) else row
        }.toImmutableList()
        return state.copy(
            messageQueues = persistentMapOf("chat" to queue.copy(messages = messages)),
            attachmentPreviews = persistentMapOf("image" to AttachmentPreviewUi(imageBytes = attachmentImageFixture())),
        )
    }
}
