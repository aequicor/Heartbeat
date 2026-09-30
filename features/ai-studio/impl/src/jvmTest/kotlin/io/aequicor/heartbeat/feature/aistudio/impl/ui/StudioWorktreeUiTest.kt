package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.BuildPhaseUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.BuildUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EnvironmentUi
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
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_result
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_this_computer
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class StudioWorktreeUiTest {
    @Test
    fun `recovery explains known failures and unknown diagnostics without exposing raw codes`() =
        runSkikoComposeUiTest(size = Size(520f, 700f)) {
            val pane = PaneUi(0, sessionId = "chat")
            var failure by mutableStateOf("OriginalCheckoutDirty")
            var dirtyLabel = ""
            var unknownLabel = ""
            setContent {
                HbTheme(darkTheme = false) {
                    dirtyLabel = stringResource(Res.string.worktree_failure_checkout_dirty)
                    unknownLabel = stringResource(Res.string.worktree_failure_unknown)
                    val task = WorktreeUi(
                        WorktreePhaseUi.RecoveryRequired,
                        "codex/task",
                        "master",
                        null,
                        null,
                        failure,
                        persistentListOf(),
                    )
                    StudioWorktree(savedWorkspace(pane, task).paneContent(pane), {})
                }
            }
            onNodeWithText(dirtyLabel).assertIsDisplayed()
            onNodeWithText("OriginalCheckoutDirty").assertDoesNotExist()
            runOnIdle { failure = "UnexpectedInternalFailure" }
            onNodeWithText(unknownLabel).assertIsDisplayed()
            onNodeWithText("UnexpectedInternalFailure").assertDoesNotExist()
            onNodeWithTag("worktree-action-Merge").assertDoesNotExist()
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
                            "codex/branch-with-an-extremely-long-descriptive-name-for-horizontal-scrolling",
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
                editor.performClick().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
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
    fun `long branch decisions remain visible at compact and desktop widths in both themes`() {
        for (dark in listOf(false, true)) {
            for (width in listOf(520f, 1280f)) {
                runSkikoComposeUiTest(size = Size(width, 700f)) {
                    val events = mutableListOf<AiStudioScreenIntent>()
                    val pane = PaneUi(0, sessionId = "chat")
                    val task = completedLongBranchTask()
                    val initial = workspace(pane)
                    var resultLabel = ""
                    val state = initial.copy(
                        sessions = persistentListOf(
                            SessionUi("chat", "Feature", "project", Instant.DISTANT_PAST, isWorktree = true),
                        ),
                        worktrees = persistentMapOf("chat" to task),
                    )
                    setContent {
                        HbTheme(darkTheme = dark) {
                            resultLabel = stringResource(Res.string.worktree_result)
                            Box(
                                Modifier.fillMaxSize()
                                    .background(HbTheme.surfaces.backdrop)
                                    .padding(HbTheme.spacing.xl),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                HbColumn {
                                    StudioWorktree(state.paneContent(pane), events::add, Modifier)
                                    StudioComposer(state.paneContent(pane), events::add, isCompact = width < 900f)
                                }
                            }
                        }
                    }
                    onNodeWithTag("worktree-result-chat").assertIsDisplayed()
                    onNodeWithText(resultLabel).assertIsDisplayed()
                    val target = onNodeWithTag("worktree-merge-target").assertIsDisplayed()
                    assertTrue(
                        target.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                            .contains(checkNotNull(task.sourceBranch)),
                    )
                    WorktreeActionUi.entries.forEach {
                        val action = onNodeWithTag("worktree-action-${it.name}")
                        action.assertIsDisplayed()
                        val bounds = action.fetchSemanticsNode().boundsInRoot
                        assertTrue(bounds.left >= 0f && bounds.right <= width)
                        action.performClick()
                    }
                    onNodeWithText(resultLabel).assertIsDisplayed()
                    runOnIdle {
                        assertEquals<List<AiStudioScreenIntent>>(
                            WorktreeActionUi.entries.map {
                                AiStudioScreenIntent.DecideWorktree("chat", it)
                            },
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
                val initial = workspace(pane).copy(
                    sessions = persistentListOf(SessionUi("chat", "Feature", "project", Instant.DISTANT_PAST)),
                )
                var task by mutableStateOf(
                    WorktreeUi(
                        WorktreePhaseUi.AwaitingDecision,
                        "codex/task",
                        null,
                        "Done",
                        null,
                        null,
                        persistentListOf(),
                    ),
                )
                setContent {
                    HbTheme(darkTheme = dark) {
                        Box(
                            Modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).padding(HbTheme.spacing.xl),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            StudioWorktree(
                                initial.copy(worktrees = persistentMapOf("chat" to task)).paneContent(pane),
                                {},
                                Modifier,
                            )
                        }
                    }
                }
                onNodeWithTag("worktree-action-Merge").assertDoesNotExist()
                saveImage(captureToImage(), "detached-$dark")
                runOnIdle { task = task.copy(phase = WorktreePhaseUi.Idle) }
                onNodeWithTag("worktree-action-CreatePr").assertDoesNotExist()
            }
        }
    }

    @Test
    fun `journal recovery hides stale decisions and survives removing the pane from composition`() =
        runSkikoComposeUiTest(size = Size(520f, 700f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val pane = PaneUi(0, sessionId = "chat")
            val initial = savedWorkspace(
                pane,
                WorktreeUi(
                    WorktreePhaseUi.AwaitingDecision,
                    "codex/task",
                    "master",
                    "Done",
                    null,
                    null,
                    persistentListOf(),
                ),
            )
            var journal by mutableStateOf(WorktreeJournalUi.Loading)
            var isShown by mutableStateOf(true)
            var hasTask by mutableStateOf(false)
            setContent {
                HbTheme(darkTheme = false) {
                    if (isShown) {
                        StudioWorktree(
                            initial.copy(
                                worktreeJournal = journal,
                                worktrees = if (hasTask) initial.worktrees else persistentMapOf(),
                            ).paneContent(pane),
                            events::add,
                            Modifier,
                        )
                    }
                }
            }
            onNodeWithTag("worktree-journal-loading").assertIsDisplayed()
            WorktreeActionUi.entries.forEach { onNodeWithTag("worktree-action-${it.name}").assertDoesNotExist() }
            runOnIdle {
                hasTask = true
                journal = WorktreeJournalUi.Error
            }
            onNodeWithTag("worktree-journal-error").assertIsDisplayed()
            WorktreeActionUi.entries.forEach { onNodeWithTag("worktree-action-${it.name}").assertDoesNotExist() }
            onNodeWithTag("worktree-journal-retry").performClick()
            runOnIdle {
                assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.RetryWorktreeJournal), events)
                isShown = false
                journal = WorktreeJournalUi.Ready
            }
            onNodeWithTag("worktree-result-chat").assertDoesNotExist()
            runOnIdle { isShown = true }
            WorktreeActionUi.entries.forEach { onNodeWithTag("worktree-action-${it.name}").assertIsDisplayed() }
            onNodeWithTag("worktree-journal-error").assertDoesNotExist()
        }

    @Test
    fun `failed build exposes bounded tail diagnostics and queued build can be cancelled`() =
        runSkikoComposeUiTest(size = Size(520f, 700f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val pane = PaneUi(0, sessionId = "chat")
            val diagnostic = (0..99).joinToString(
                "\n",
            ) { "Log line $it: ${"details ".repeat(8)}" } + "\nCompilation failed at Main.kt:42"
            val failed = BuildUi("failed", "compile", BuildPhaseUi.Failed, diagnostic, "Exit1")
            val queued = BuildUi("queued", "test", BuildPhaseUi.Queued, "", null, queuePosition = 2)
            val task = WorktreeUi(
                WorktreePhaseUi.Idle,
                "codex/task",
                "master",
                null,
                null,
                null,
                persistentListOf(failed, queued),
            )
            val initial = savedWorkspace(pane, task)
            var journal by mutableStateOf(WorktreeJournalUi.Ready)
            setContent {
                HbTheme(darkTheme = false) {
                    StudioWorktree(initial.copy(worktreeJournal = journal).paneContent(pane), events::add, Modifier)
                }
            }
            val shown = onNodeWithTag(
                "build-output-failed",
            ).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            assertTrue(shown.endsWith("Compilation failed at Main.kt:42"))
            assertTrue(shown.length <= 4000 && shown.lines().size <= 40)
            assertTrue(!shown.contains("Log line 0:"))
            onNodeWithTag("cancel-build-queued").performScrollTo().performClick()
            runOnIdle {
                assertEquals(
                    listOf<AiStudioScreenIntent>(AiStudioScreenIntent.CancelWorktreeBuild("chat", "queued")),
                    events,
                )
                journal = WorktreeJournalUi.Error
            }
            onNodeWithTag("cancel-build-queued").assertDoesNotExist()
            onNodeWithTag("build-output-failed").assertExists()
        }

    private fun savedWorkspace(pane: PaneUi, task: WorktreeUi) = workspace(pane).copy(
        sessions = persistentListOf(SessionUi("chat", "Feature", "project", Instant.DISTANT_PAST, isWorktree = true)),
        worktrees = persistentMapOf("chat" to task),
    )

    private fun completedLongBranchTask() = WorktreeUi(
        WorktreePhaseUi.AwaitingDecision,
        "codex/task-with-a-long-descriptive-branch-name",
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

private fun saveImage(image: ImageBitmap, name: String) {
    val directory = File("build/worktree-ui").apply { mkdirs() }
    check(ImageIO.write(image.toAwtImage(), "png", File(directory, "$name.png")))
}
