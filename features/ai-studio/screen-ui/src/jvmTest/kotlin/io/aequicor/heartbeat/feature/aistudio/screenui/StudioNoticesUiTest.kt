package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioNoticeUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.dismissNotice
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/** Closing passive messages preserves the composer and leaves the underlying chat restrictions in force. */
@OptIn(ExperimentalTestApi::class)
class StudioNoticesUiTest {
    private val previousLocale = Locale.getDefault()

    @BeforeTest
    fun useRussianLabels() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
    }

    @AfterTest
    fun restoreLanguage() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `closing a failed submission keeps its recovered draft and other notices and a later failure returns`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            var state by mutableStateOf(noticeWorkspace())
            val dismissals = mutableListOf<StudioIntent.DismissNotice>()
            setContent {
                NoticePane(state, onIntent = { intent ->
                    if (intent is StudioIntent.DismissNotice) {
                        dismissals += intent
                        state = state.dismissNotice(intent)
                    }
                })
            }

            onNodeWithTag("dismiss-notice-SubmitFailed-0").assertIsDisplayed().performClick()

            onNodeWithTag("notice-SubmitFailed-0").assertDoesNotExist()
            onNodeWithTag("notice-AttachmentFailed-0").assertIsDisplayed()
            assertEquals(
                RECOVERED_DRAFT,
                onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
            )
            runOnIdle {
                assertEquals(listOf(StudioIntent.DismissNotice(0, StudioNoticeUi.SubmitFailed)), dismissals)
                assertEquals(RECOVERED_DRAFT, state.draft(0))
                state = state.copy(failedPanes = persistentSetOf(0))
            }
            onNodeWithTag("notice-SubmitFailed-0").assertIsDisplayed()
            onNodeWithTag("notice-AttachmentFailed-0").assertIsDisplayed()
        }

    @Test
    fun `closing a read only notification leaves the chat read only`() =
        runSkikoComposeUiTest(size = Size(1280f, 800f)) {
            val workspace = noticeWorkspace()
            var state by mutableStateOf(
                workspace.copy(
                    sessions = persistentListOf(workspace.sessions.single().copy(isContinuable = false)),
                ),
            )
            setContent {
                NoticePane(state, onIntent = { intent ->
                    if (intent is StudioIntent.DismissNotice) state = state.dismissNotice(intent)
                })
            }

            onNodeWithText(RECOVERED_DRAFT).assertIsDisplayed().assertIsNotEnabled()
            onNodeWithTag("dismiss-notice-ReadOnly-0").assertIsDisplayed().performClick()

            onNodeWithTag("notice-ReadOnly-0").assertDoesNotExist()
            onNodeWithText(RECOVERED_DRAFT).assertIsDisplayed().assertIsNotEnabled()
            runOnIdle {
                assertEquals(false, state.sessions.single().isContinuable)
                assertEquals(RECOVERED_DRAFT, state.draft(0))
            }
        }

    @Test
    fun `close buttons fit compact and desktop notices in both themes and remain keyboard operable`() {
        for (width in listOf(420f, 1280f)) {
            for (dark in listOf(false, true)) renderNotices(width, dark)
        }
    }

    private fun renderNotices(width: Float, dark: Boolean) = runSkikoComposeUiTest(size = Size(width, 800f)) {
        var state by mutableStateOf(noticeWorkspace())
        setContent {
            NoticePane(state, compact = width < 720f, dark = dark, onIntent = { intent ->
                if (intent is StudioIntent.DismissNotice) state = state.dismissNotice(intent)
            })
        }
        for (notice in listOf(StudioNoticeUi.SubmitFailed, StudioNoticeUi.AttachmentFailed)) {
            val banner = onNodeWithTag("notice-${notice.name}-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val close = onNodeWithTag("dismiss-notice-${notice.name}-0")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(banner.left >= 0f && banner.right <= width, "Notice must fit inside the pane")
            assertTrue(close.left >= banner.left && close.right <= banner.right, "Close must fit inside the notice")
            assertTrue(close.top >= banner.top && close.bottom <= banner.bottom, "Close must not overlap adjacent rows")
            if (width < 720f) assertTrue(close.width >= 44f && close.height >= 44f, "Close needs a mobile hit target")
        }
        saveNotices("notices-${width.toInt()}-$dark")

        onNodeWithTag("dismiss-notice-SubmitFailed-0").requestFocus().assertIsFocused()
            .performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("dismiss-notice-AttachmentFailed-0").assertIsFocused()
        saveNotices("focused-${width.toInt()}-$dark")
        onNodeWithTag("dismiss-notice-AttachmentFailed-0").performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("notice-AttachmentFailed-0").assertDoesNotExist()
        onNodeWithTag("notice-SubmitFailed-0").assertIsDisplayed()
    }

    private fun SkikoComposeUiTest.saveNotices(name: String) {
        val directory = File("build/reports/studio-notices").apply { mkdirs() }
        check(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png")))
    }
}

@Composable
private fun NoticePane(
    state: AiStudioScreenState,
    compact: Boolean = false,
    dark: Boolean = false,
    onIntent: (StudioIntent) -> Unit,
) {
    HbTheme(darkTheme = dark, dimensions = if (compact) HbDimensions.Mobile else HbDimensions.Desktop) {
        StudioPaneView(
            state.paneContent(state.panes.single()),
            onIntent,
            PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = compact),
            Modifier.fillMaxSize(),
        )
    }
}

private fun noticeWorkspace() = AiStudioScreenState(
    panes = persistentListOf(PaneUi(0, sessionId = "chat")),
    sessions = persistentListOf(SessionUi("chat", "Обсуждение интерфейса", null, Instant.fromEpochSeconds(0))),
    models = StudioModelOptions,
    drafts = persistentMapOf("chat" to RECOVERED_DRAFT),
    failedPanes = persistentSetOf(0),
    attachmentErrorPanes = persistentSetOf(0),
)

private const val RECOVERED_DRAFT = "Помоги улучшить интерфейс — текст запроса восстановлен."
