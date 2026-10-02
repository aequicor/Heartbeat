package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.BuildPhaseUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.BuildUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeActionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeJournalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreePhaseUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withDraft
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_checkout_dirty
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_merge_target
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_preparing
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_result
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_this_computer
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Worktree lifecycle renders as cards in the transcript; the composer region keeps only the composer. */
@OptIn(ExperimentalTestApi::class)
class StudioWorktreeUiTest {
    @Test
    fun `recovery explains known failures in the feed without exposing raw codes`() =
        runSkikoComposeUiTest(size = Size(520f, 700f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val pane = PaneUi(0, sessionId = "chat")
            var failure by mutableStateOf("OriginalCheckoutDirty")
            var dirtyLabel = ""
            var unknownLabel = ""
            setContent {
                dirtyLabel = stringResource(Res.string.worktree_failure_checkout_dirty)
                unknownLabel = stringResource(Res.string.worktree_failure_unknown)
                val task = task(WorktreePhaseUi.RecoveryRequired).copy(failure = failure)
                PaneHost(savedWorkspace(pane, task), pane, events::add)
            }
            val taskCard = hasTestTag(card("worktree:chat"))
            onNode(taskCard and hasAnyAncestor(hasTestTag("transcript-chat"))).assertIsDisplayed()
            onNode(hasAnyAncestor(hasTestTag("pane-footer-0")) and taskCard).assertDoesNotExist()
            onNodeWithText(dirtyLabel).assertIsDisplayed()
            onNodeWithText("OriginalCheckoutDirty").assertDoesNotExist()
            runOnIdle { failure = "UnexpectedInternalFailure" }
            onNodeWithText(unknownLabel).assertIsDisplayed()
            onNodeWithText("UnexpectedInternalFailure").assertDoesNotExist()
            onNodeWithTag(decision("Merge")).assertDoesNotExist()
            onNodeWithTag(action("worktree:chat", RECHECK_ID)).performClick()
            runOnIdle {
                assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.RecheckWorktree("chat")), events)
            }
        }

    @Test
    fun `a pull request link that no browser opens keeps the pane alive`() =
        runSkikoComposeUiTest(size = Size(520f, 700f)) {
            val pane = PaneUi(0, sessionId = "chat")
            val opened = mutableListOf<String>()
            val browser = object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                    throw IOException("No default browser")
                }
            }
            val task = task(WorktreePhaseUi.Retained).copy(pullRequestUrl = "https://example.test/pr/9")
            setContent {
                CompositionLocalProvider(LocalUriHandler provides browser) {
                    PaneHost(savedWorkspace(pane, task), pane, onIntent = {})
                }
            }
            onNodeWithTag(action("worktree:chat", OPEN_PR_ID)).performClick()
            runOnIdle { assertEquals(listOf("https://example.test/pr/9"), opened) }
            onNodeWithTag(card("worktree:chat")).assertIsDisplayed()
        }

    @Test
    fun `compact composer keeps selected context and scrolling cannot consume keyboard submit`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(520f, 700f)) {
                val events = mutableListOf<AiStudioScreenIntent>()
                val pane = PaneUi(7, projectId = "project", isWorktree = true)
                var state by mutableStateOf(
                    workspace(pane).copy(isWorktreeAvailable = true).withDraft(7, "Inspect isolation"),
                )
                var computerLabel = ""
                setContent {
                    computerLabel = stringResource(Res.string.worktree_this_computer)
                    HbTheme(darkTheme = dark) {
                        Box(
                            Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).padding(HbTheme.spacing.xl),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            StudioComposer(state.paneContent(state.panes.single()), events::add, isCompact = true)
                        }
                    }
                }
                onNodeWithText("Heartbeat").assertIsDisplayed()
                onNodeWithText(computerLabel).assertIsDisplayed()
                onNodeWithTag("worktree-mode-7").assertIsOn()
                saveImage(captureToImage(), "composer-new-520-$dark")
                runOnIdle {
                    state = savedWorkspace(
                        PaneUi(7, sessionId = "chat"),
                        WorktreeUi(
                            WorktreePhaseUi.Idle,
                            "heartbeat/branch-with-an-extremely-long-descriptive-name-for-horizontal-scrolling",
                            "master",
                            null,
                            null,
                            null,
                            persistentListOf(),
                        ),
                    ).withDraft(7, "Inspect isolation")
                }
                onNodeWithTag("worktree-pinned-7").assertIsDisplayed()
                saveImage(captureToImage(), "composer-pinned-520-$dark")
                onNodeWithTag("worktree-branch-7").performScrollTo().assertIsDisplayed()
                val editor = onNode(hasAnyAncestor(hasTestTag("composer-7")) and hasSetTextAction())
                editor.performClick()
                saveImage(captureToImage(), "composer-focus-520-$dark")
                editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
                runOnIdle {
                    assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.Submit(7)), events)
                    assertEquals("Inspect isolation", state.draft(7))
                }
            }
        }
    }

    @Test
    fun `new pane mode selection has its own identity and disappears when unavailable`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val pane = PaneUi(7, projectId = "project")
            var state by mutableStateOf(workspace(pane).copy(isWorktreeAvailable = true))
            setContent {
                HbTheme(darkTheme = false) {
                    Box(
                        Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).padding(HbTheme.spacing.xl),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        StudioComposer(state.paneContent(pane), events::add, isCompact = false)
                    }
                }
            }
            onNodeWithTag("worktree-mode-7").performClick()
            runOnIdle {
                assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.SelectWorktree(7, true)), events)
                state = state.copy(isWorktreeAvailable = false)
            }
            onNodeWithTag("worktree-mode-7").assertDoesNotExist()
        }

    @Test
    fun `long branch decisions remain visible in the feed at compact and desktop widths in both themes`() {
        for (dark in listOf(false, true)) {
            for (width in listOf(520f, 1280f)) {
                runSkikoComposeUiTest(size = Size(width, 700f)) {
                    val events = mutableListOf<AiStudioScreenIntent>()
                    val pane = PaneUi(0, sessionId = "chat")
                    val task = completedLongBranchTask()
                    var resultLabel = ""
                    var targetLabel = ""
                    setContent {
                        resultLabel = stringResource(Res.string.worktree_result)
                        targetLabel = stringResource(Res.string.worktree_merge_target, checkNotNull(task.sourceBranch))
                        PaneHost(savedWorkspace(pane, task), pane, events::add, isDark = dark, isCompact = width < 900f)
                    }
                    onNodeWithTag(card("worktree:chat")).assertIsDisplayed()
                    onNodeWithText(resultLabel).assertIsDisplayed()
                    onNodeWithText(targetLabel, substring = true).assertIsDisplayed()
                    onNodeWithText(checkNotNull(task.summary), substring = true).assertIsDisplayed()
                    WorktreeActionUi.entries.forEach {
                        val action = onNodeWithTag(decision(it.name))
                        action.assertIsDisplayed()
                        val bounds = action.fetchSemanticsNode().boundsInRoot
                        assertTrue(bounds.left >= 0f && bounds.right <= width)
                        action.performClick()
                    }
                    onNodeWithText(resultLabel).assertIsDisplayed()
                    runOnIdle {
                        assertEquals<List<AiStudioScreenIntent>>(
                            WorktreeActionUi.entries.map { AiStudioScreenIntent.DecideWorktree("chat", it) },
                            events,
                        )
                    }
                    saveImage(captureToImage(), "result-${width.toInt()}-$dark")
                }
            }
        }
    }

    @Test
    fun `detached checkout hides local merge and idle chat hides result decisions`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(520f, 700f)) {
                val pane = PaneUi(0, sessionId = "chat")
                var task by mutableStateOf(task(WorktreePhaseUi.AwaitingDecision).copy(sourceBranch = null))
                setContent { PaneHost(savedWorkspace(pane, task), pane, {}, isDark = dark) }
                onNodeWithTag(decision("CreatePr")).assertIsDisplayed()
                onNodeWithTag(decision("Merge")).assertDoesNotExist()
                saveImage(captureToImage(), "detached-$dark")
                runOnIdle { task = task.copy(phase = WorktreePhaseUi.Idle) }
                onNodeWithTag(decision("CreatePr")).assertDoesNotExist()
                onNodeWithTag(card("worktree:chat")).assertDoesNotExist()
            }
        }
    }

    @Test
    fun `journal notices replace stale decisions in the feed and survive removing the pane`() =
        runSkikoComposeUiTest(size = Size(520f, 700f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val pane = PaneUi(0, sessionId = "chat")
            val initial = savedWorkspace(pane, task(WorktreePhaseUi.AwaitingDecision))
            var journal by mutableStateOf(WorktreeJournalUi.Loading)
            var isShown by mutableStateOf(true)
            var hasTask by mutableStateOf(false)
            setContent {
                if (isShown) {
                    val state = initial.copy(
                        worktreeJournal = journal,
                        worktrees = if (hasTask) initial.worktrees else persistentMapOf(),
                    )
                    PaneHost(state, pane, events::add)
                }
            }
            val loadingCard = hasTestTag(card(JOURNAL_LOADING_ID))
            onNode(loadingCard and hasAnyAncestor(hasTestTag("transcript-chat"))).assertIsDisplayed()
            WorktreeActionUi.entries.forEach { onNodeWithTag(decision(it.name)).assertDoesNotExist() }
            runOnIdle {
                hasTask = true
                journal = WorktreeJournalUi.Error
            }
            onNodeWithTag(card(JOURNAL_ERROR_ID)).assertIsDisplayed()
            onNodeWithTag(card(JOURNAL_LOADING_ID)).assertDoesNotExist()
            WorktreeActionUi.entries.forEach { onNodeWithTag(decision(it.name)).assertDoesNotExist() }
            onNodeWithTag(action(JOURNAL_ERROR_ID, JOURNAL_RETRY_ID)).performClick()
            runOnIdle {
                assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.RetryWorktreeJournal), events)
                isShown = false
                journal = WorktreeJournalUi.Ready
            }
            onNodeWithTag(card("worktree:chat")).assertDoesNotExist()
            runOnIdle { isShown = true }
            WorktreeActionUi.entries.forEach { onNodeWithTag(decision(it.name)).assertIsDisplayed() }
            onNodeWithTag(card(JOURNAL_ERROR_ID)).assertDoesNotExist()
        }

    @Test
    fun `failed build reveals its bounded log tail in the feed and queued build can be cancelled`() =
        runSkikoComposeUiTest(size = Size(520f, 900f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val pane = PaneUi(0, sessionId = "chat")
            val diagnostic = (0..99).joinToString("\n") { "Log line $it: ${"details ".repeat(8)}" } +
                "\nCompilation failed at Main.kt:42"
            val failed = BuildUi("failed", "compile", BuildPhaseUi.Failed, diagnostic, "Exit1")
            val queued = BuildUi("queued", "test", BuildPhaseUi.Queued, "", null, queuePosition = 2)
            val builds = persistentListOf(failed, queued)
            val initial = savedWorkspace(pane, task(WorktreePhaseUi.Idle).copy(builds = builds))
            var journal by mutableStateOf(WorktreeJournalUi.Ready)
            setContent { PaneHost(initial.copy(worktreeJournal = journal), pane, events::add) }
            onNodeWithTag(action("build:queued", "cancel-build-queued")).assertIsDisplayed().performClick()
            runOnIdle {
                assertEquals(
                    listOf<AiStudioScreenIntent>(AiStudioScreenIntent.CancelWorktreeBuild("chat", "queued")),
                    events,
                )
                journal = WorktreeJournalUi.Error
            }
            onNodeWithTag(card("build:queued")).assertIsDisplayed()
            onNodeWithTag(action("build:queued", "cancel-build-queued")).assertDoesNotExist()
            onNodeWithTag(block("build-output-failed")).assertDoesNotExist()
            onNodeWithTag(card("build:failed")).performClick()
            assertTrue(onAllNodesWithTag(block("build-output-failed")).fetchSemanticsNodes().isNotEmpty())
            val lastLine = hasText("Compilation failed at Main.kt:42", substring = true)
            onNode(hasScrollToIndexAction()).performScrollToNode(lastLine)
            onNode(lastLine and hasTestTag(block("build-output-failed"))).assertIsDisplayed()
            onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(card("build:failed")))
            assertTrue(onAllNodesWithText("Log line 0:", substring = true).fetchSemanticsNodes().isEmpty())
            saveImage(captureToImage(), "builds-journal-error")
        }

    @Test
    fun `a pane preparing a worktree session shows the preparation in its feed`() {
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(1280f, 700f)) {
                val pane = PaneUi(7, projectId = "project", isCreating = true, isWorktree = true)
                var preparingLabel = ""
                setContent {
                    preparingLabel = stringResource(Res.string.worktree_preparing)
                    PaneHost(workspace(pane), pane, {}, isDark = dark)
                }
                onNode(hasTestTag(card("worktree:pane-7")) and hasAnyAncestor(hasTestTag("transcript-pane-7")))
                    .assertIsDisplayed()
                onNodeWithText(preparingLabel).assertIsDisplayed()
                onNode(hasAnyAncestor(hasTestTag("pane-footer-7")) and hasTestTag(card("worktree:pane-7")))
                    .assertDoesNotExist()
                saveImage(captureToImage(), "preparing-$dark")
            }
        }
    }

    @Composable
    private fun PaneHost(
        state: AiStudioScreenState,
        pane: PaneUi,
        onIntent: (AiStudioScreenIntent) -> Unit,
        isDark: Boolean = false,
        isCompact: Boolean = true,
    ) {
        HbTheme(darkTheme = isDark) {
            StudioPaneView(
                state.paneContent(pane),
                onIntent,
                PaneLayout(isSplitAllowed = false, isCloseAllowed = false, isCompact = isCompact),
                Modifier.fillMaxSize(),
            )
        }
    }

    private fun savedWorkspace(pane: PaneUi, task: WorktreeUi) = workspace(pane).copy(
        sessions = persistentListOf(SessionUi("chat", "Feature", "project", Instant.DISTANT_PAST, isWorktree = true)),
        worktrees = persistentMapOf("chat" to task),
        transcripts = persistentMapOf(
            "chat" to persistentListOf(
                MessageUi.Prompt("prompt", Instant.DISTANT_PAST, "Implement the task", isTimestampKnown = false),
                MessageUi.Reply(
                    "reply",
                    Instant.DISTANT_PAST,
                    "Done.",
                    persistentListOf(),
                    isStreaming = false,
                    isTimestampKnown = false,
                ),
            ),
        ),
    )

    private fun task(phase: WorktreePhaseUi) = WorktreeUi(
        phase,
        "heartbeat/task",
        "master",
        "Done",
        null,
        null,
        persistentListOf(),
    )

    private fun completedLongBranchTask() = WorktreeUi(
        WorktreePhaseUi.AwaitingDecision,
        "heartbeat/task-with-a-long-descriptive-branch-name",
        "release/master-with-a-long-descriptive-name-and-several-branch-segments",
        "Implementation and verification completed.",
        null,
        null,
        persistentListOf(),
    )

    private fun workspace(pane: PaneUi): AiStudioScreenState {
        val initial = AiStudioScreenState(panes = persistentListOf(pane))
        return initial.copy(
            projects = persistentListOf(ProjectUi("project", "Heartbeat", EnvironmentUi.Local, "master")),
            models = persistentListOf(ModelUi("route", "Model", isLocalProjectSupported = true)),
            settings = initial.settings.copy(modelId = "route"),
        )
    }
}

/** Tag of the disclosure header of worktree card [id]. */
private fun card(id: String) = "tool:$id"

/** Tag of action [actionId] on worktree card [cardId]. */
private fun action(cardId: String, actionId: String) = "tool-action:$cardId:$actionId"

/** Tag of a result decision on the task card of session `chat`. */
private fun decision(name: String) = action("worktree:chat", "worktree-action-$name")

/** Tag of every content row of block [id]. */
private fun block(id: String) = "tool-block:$id"

private fun saveImage(image: ImageBitmap, name: String) {
    val directory = File("build/worktree-ui").apply { mkdirs() }
    check(ImageIO.write(image.toAwtImage(), "png", File(directory, "$name.png")))
}
