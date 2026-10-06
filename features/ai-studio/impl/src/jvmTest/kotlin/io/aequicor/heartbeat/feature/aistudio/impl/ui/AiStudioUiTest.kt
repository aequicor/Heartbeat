package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.data.studioSeed
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PermissionOptionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PermissionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionWaitUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarMode
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioModelOptions
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.toUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.withWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_add
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_checklist_waiting
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_running
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_sleeping
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_waiting_event
import io.aequicor.heartbeat.feature.aistudio.impl.resources.stopping
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class AiStudioUiTest {
    private val now = Instant.fromEpochSeconds(1_790_000_000)
    private val seed = studioSeed(now)
    private val workspace = AiStudioScreenState(phase = StudioPhase.Ready, models = StudioModelOptions)
        .withWorkspace(StudioWorkspace(seed.projects, seed.sessions))
    private val exits = StudioExits(onBack = {}, onOpenToggles = {})

    @Test
    fun `rail opens profile settings`() = runSkikoComposeUiTest(size = Size(1280f, 900f)) {
        var isOpened = false
        val actions = StudioExits(onBack = {}, onOpenToggles = {}, onOpenProfileSettings = { isOpened = true })
        setContent { HbTheme(darkTheme = false) { AiStudioContent(workspace, {}, actions) } }
        onNodeWithTag("rail-profile-settings").performClick()
        assertTrue(isOpened)
    }

    @Test
    fun `one settings button replaces the separate settings actions`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        var opened = 0
        val actions = StudioExits(
            onBack = {},
            onOpenToggles = {},
            onOpenProfileSettings = {},
            onOpenConnections = {},
            onOpenSettings = { opened++ },
        )
        setContent { HbTheme(darkTheme = false) { AiStudioContent(workspace, {}, actions) } }
        onNodeWithTag("rail-toggles").assertDoesNotExist()
        onNodeWithTag("rail-profile-settings").assertDoesNotExist()
        onNodeWithTag("rail-connections").assertDoesNotExist()
        onNodeWithTag("rail-settings").performClick()
        runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `rail hides profile settings while search tools are off`() = runSkikoComposeUiTest(size = Size(1280f, 900f)) {
        setContent { HbTheme(darkTheme = false) { AiStudioContent(workspace, {}, exits) } }
        onNodeWithTag("rail-profile-settings").assertDoesNotExist()
    }

    @Test
    fun `wide workspace shows the unified sidebar, grouped sessions and the open transcript`() =
        runSkikoComposeUiTest(size = Size(1280f, 900f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            val state = workspace.copy(
                panes = persistentListOf(PaneUi(0, sessionId = "s-facade")),
                transcripts = transcriptsOf("s-facade"),
            )
            setContent { HbTheme(darkTheme = false) { AiStudioContent(state, events::add, exits) } }

            onNodeWithTag("sidebar-footer").assertIsDisplayed()
            onNodeWithTag("studio-sidebar").assertIsDisplayed()
            onNodeWithTag("transcript-s-facade").assertIsDisplayed()
            val paneBounds = onNodeWithTag("pane-0").fetchSemanticsNode().boundsInRoot
            val transcriptBounds = onNodeWithTag("transcript-s-facade").fetchSemanticsNode().boundsInRoot
            assertEquals(paneBounds.top, transcriptBounds.top)
            assertEquals(paneBounds.bottom, transcriptBounds.bottom)
            onAllNodesWithTag("session-s-facade").onFirst()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            save("studio-wide", captureToImage().toAwtImage())

            onAllNodesWithTag("session-s-adr").onFirst().performClick()
            onNodeWithTag("pane-split").performClick()
            onNodeWithTag("sidebar-search-open").performClick()
            onNodeWithTag("sidebar-new-session").performClick()
            assertEquals(
                listOf<AiStudioScreenIntent>(
                    AiStudioScreenIntent.OpenSession("s-adr"),
                    AiStudioScreenIntent.OpenBeside(null),
                    AiStudioScreenIntent.ToggleSearch,
                    AiStudioScreenIntent.NewSession("p-heartbeat"),
                ),
                events,
            )
        }

    @Test
    fun `engine connections are offered in the rail only when enabled`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        var opened = 0
        var isEnabled by mutableStateOf(false)
        setContent {
            val open: () -> Unit = { opened++ }
            val exits = StudioExits(
                onBack = {},
                onOpenToggles = {},
                onOpenProfileSettings = {},
                onOpenConnections = open.takeIf { isEnabled },
            )
            HbTheme(darkTheme = false) { AiStudioContent(workspace, {}, exits) }
        }
        onNodeWithTag("rail-connections").assertDoesNotExist()
        isEnabled = true
        onNodeWithTag("rail-connections").performClick()
        runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `new session page offers the project selector and the composer controls`() =
        runSkikoComposeUiTest(size = Size(1280f, 900f)) {
            val state = workspace.copy(panes = persistentListOf(PaneUi(0, projectId = "p-heartbeat")))
            setContent { HbTheme(darkTheme = false) { AiStudioContent(state, {}, exits) } }
            onNodeWithTag("new-session-hero").assertIsDisplayed()
            onNodeWithTag("project-chip-0").assertIsDisplayed()
            onNodeWithTag("model-chip").assertIsDisplayed()
            save("studio-new-session", captureToImage().toAwtImage())
        }

    @Test
    fun `project menu offers the folder picker only when enabled`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        val events = mutableListOf<AiStudioScreenIntent>()
        var addLabel = ""
        var state by mutableStateOf(
            workspace.copy(panes = persistentListOf(PaneUi(0)), isProjectAddingAvailable = true),
        )
        setContent {
            addLabel = stringResource(Res.string.project_add)
            HbTheme(darkTheme = false) { AiStudioContent(state, events::add, exits) }
        }
        onNodeWithTag("project-chip-0").performClick()
        onNodeWithText(addLabel).performClick()
        runOnIdle {
            assertEquals(listOf<AiStudioScreenIntent>(AiStudioScreenIntent.AddProject(0)), events)
            state = state.copy(isProjectAddingAvailable = false)
        }
        onNodeWithTag("project-chip-0").performClick()
        onNodeWithText(addLabel).assertDoesNotExist()
    }

    @Test
    fun `questionnaire of the asking session replaces its permission buttons`() =
        runSkikoComposeUiTest(size = Size(1280f, 900f)) {
            val state = workspace.copy(
                panes = persistentListOf(PaneUi(0, sessionId = "s-facade")),
                running = persistentSetOf("s-facade"),
                transcripts = transcriptsOf("s-facade"),
                permissions = persistentListOf(
                    PermissionUi("s-facade", "request", "Pick", persistentListOf(PermissionOptionUi("once", "Once"))),
                ),
            )
            val questions = object : ComposableComponent {
                @Composable
                override fun Content(modifier: Modifier) = Box(modifier)
            }
            val asking = exits.copy(questions = persistentMapOf("s-facade" to questions))
            setContent { HbTheme(darkTheme = false) { AiStudioContent(state, {}, asking) } }
            onNodeWithTag("pane-questionnaire").assertExists()
            onNodeWithTag("permission-request").assertDoesNotExist()
        }

    @Test
    fun `pending permission offers its exact options`() = runSkikoComposeUiTest(size = Size(1280f, 900f)) {
        val events = mutableListOf<AiStudioScreenIntent>()
        val state = workspace.copy(
            panes = persistentListOf(PaneUi(0, sessionId = "s-facade")),
            running = persistentSetOf("s-facade"),
            transcripts = transcriptsOf("s-facade"),
            permissions = persistentListOf(
                PermissionUi(
                    "s-facade",
                    "request",
                    "Run tests",
                    persistentListOf(PermissionOptionUi("once", "Once")),
                    description = "./gradlew jvmTest",
                ),
            ),
        )
        setContent { HbTheme(darkTheme = false) { AiStudioContent(state, events::add, exits) } }
        onNodeWithTag("permission-request").assertIsDisplayed()
        onNodeWithTag("permission-request-description").assertIsDisplayed()
        onNodeWithTag("permission-request-more").assertDoesNotExist()
        onNodeWithTag("permission-request-once").performClick()
        runOnIdle {
            assertEquals(
                listOf<AiStudioScreenIntent>(AiStudioScreenIntent.RespondPermission("s-facade", "request", "once")),
                events,
            )
        }
    }

    @Test
    fun `a long description on a low pane keeps the decision visible and says the text continues`() =
        runSkikoComposeUiTest(size = Size(1000f, 480f)) {
            val long = (1..80).joinToString("\n") { "line $it" }
            val state = workspace.copy(
                panes = persistentListOf(PaneUi(0, sessionId = "s-facade")),
                running = persistentSetOf("s-facade"),
                transcripts = transcriptsOf("s-facade"),
                permissions = persistentListOf(
                    PermissionUi(
                        "s-facade",
                        "request",
                        "Remember instruction",
                        persistentListOf(PermissionOptionUi("once", "Once"), PermissionOptionUi("deny", "Deny")),
                        description = long,
                    ),
                ),
            )
            setContent { HbTheme(darkTheme = false) { AiStudioContent(state, {}, exits) } }
            onNodeWithTag("permission-request-more").assertIsDisplayed()
            onNodeWithTag("permission-request-once").assertIsDisplayed()
            onNodeWithTag("permission-request-deny").assertIsDisplayed()
            onNodeWithTag("composer-0").assertIsDisplayed()

            // A keyboard reaches the hidden part, and the decisions stay in place once the end is reached.
            val text = onNodeWithTag("permission-request-description")
            // Unclipped position: the clipped bounds of the text stay at the top of the scrolled area.
            val top = text.fetchSemanticsNode().positionInRoot.y
            val decision = onNodeWithTag("permission-request-once").fetchSemanticsNode().boundsInRoot.top
            onNodeWithTag("permission-request-scroll").performSemanticsAction(SemanticsActions.RequestFocus)
            onNodeWithTag("permission-request-scroll").performKeyInput { repeat(20) { pressKey(Key.PageDown) } }
            waitForIdle()
            assertTrue(text.fetchSemanticsNode().positionInRoot.y < top)
            onNodeWithTag("permission-request-more").assertIsDisplayed()
            assertEquals(decision, onNodeWithTag("permission-request-once").fetchSemanticsNode().boundsInRoot.top)
        }

    @Test
    fun `awaiting native history shows working and stopping requires an explicit stop`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        var workingLabel = ""
        var stoppingLabel = ""
        var state by mutableStateOf(
            workspace.copy(
                panes = persistentListOf(PaneUi(0, sessionId = "s-facade")),
                running = persistentSetOf("s-facade"),
                transcripts = transcriptsOf("s-facade"),
                now = now,
            ),
        )
        setContent {
            workingLabel = stringResource(Res.string.session_running)
            stoppingLabel = stringResource(Res.string.stopping)
            HbTheme(darkTheme = false) { AiStudioContent(state, {}, exits) }
        }
        val runStatus = hasAnyAncestor(hasTestTag("run-status"))
        onNode(runStatus and hasText(workingLabel)).assertIsDisplayed()
        onNode(runStatus and hasText(stoppingLabel)).assertDoesNotExist()
        runOnIdle { state = state.copy(stopping = persistentSetOf("s-facade")) }
        onNode(runStatus and hasText(stoppingLabel)).assertIsDisplayed()
    }

    @Test
    fun `sleep and event wait replace running status and remain visible on hover`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        var sleepingLabel = ""
        var waitingLabel = ""
        var state by mutableStateOf(
            workspace.copy(
                panes = persistentListOf(PaneUi(0, sessionId = "s-facade")),
                running = persistentSetOf("s-facade"),
                transcripts = transcriptsOf("s-facade"),
                sessions = workspace.sessions.map {
                    if (it.id == "s-facade") it.copy(scheduledWait = SessionWaitUi.Sleeping) else it
                }.toImmutableList(),
            ),
        )
        setContent {
            sleepingLabel = stringResource(Res.string.session_sleeping)
            waitingLabel = stringResource(Res.string.session_waiting_event)
            HbTheme(darkTheme = false) { AiStudioContent(state, {}, exits) }
        }
        onNodeWithTag("run-status").assertIsDisplayed()
        onNodeWithTag("session-wait-status").assertDoesNotExist()
        runOnIdle { state = state.copy(running = persistentSetOf()) }
        onNodeWithTag("session-s-facade").performMouseInput { moveTo(center) }
        onNodeWithTag("session-waiting-s-facade", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("run-status").assertDoesNotExist()
        onNode(hasAnyAncestor(hasTestTag("session-wait-status")) and hasText(sleepingLabel)).assertIsDisplayed()
        save("studio-sleeping", captureToImage().toAwtImage())
        runOnIdle {
            state = state.copy(
                sessions = state.sessions.map {
                    if (it.id == "s-facade") it.copy(scheduledWait = SessionWaitUi.WaitingForEvent) else it
                }.toImmutableList(),
            )
        }
        onNode(hasAnyAncestor(hasTestTag("session-wait-status")) and hasText(waitingLabel)).assertIsDisplayed()
        runOnIdle { state = state.copy(running = persistentSetOf("s-facade")) }
        onNodeWithTag("run-status").assertIsDisplayed()
        onNodeWithTag("session-wait-status").assertDoesNotExist()
        runOnIdle {
            state = state.copy(
                running = persistentSetOf(),
                sessions = state.sessions.map { it.copy(scheduledWait = null) }.toImmutableList(),
            )
        }
        onNodeWithTag("session-waiting-s-facade", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("session-wait-status").assertDoesNotExist()
    }

    @Test
    fun `waiting status fits both themes and window widths and checklist remains visible on hover`() {
        for ((width, isDark) in listOf(1280f to false, 420f to false, 1280f to true, 420f to true)) {
            runSkikoComposeUiTest(size = Size(width, 900f)) {
                var checklistLabel = ""
                var state by mutableStateOf(
                    workspace.copy(
                        panes = persistentListOf(PaneUi(0, sessionId = "s-facade")),
                        transcripts = transcriptsOf("s-facade"),
                        sessions = workspace.sessions.map {
                            if (it.id == "s-facade") it.copy(scheduledWait = SessionWaitUi.WaitingForEvent) else it
                        }.toImmutableList(),
                    ),
                )
                setContent {
                    checklistLabel = stringResource(Res.string.session_checklist_waiting)
                    HbTheme(darkTheme = isDark) { AiStudioContent(state, {}, exits) }
                }
                onNodeWithTag("session-wait-status").assertIsDisplayed()
                save("studio-waiting-$width-$isDark", captureToImage().toAwtImage())
                runOnIdle { state = state.copy(sidebar = state.sidebar.copy(isDrawerOpen = true)) }
                onNodeWithTag("session-s-facade").performMouseInput { moveTo(center) }
                onNodeWithTag("session-waiting-s-facade", useUnmergedTree = true).assertIsDisplayed()
                runOnIdle {
                    state = state.copy(
                        sessions = state.sessions.map {
                            if (it.id == "s-facade") {
                                it.copy(scheduledWait = null, isAwaitingChecklist = true)
                            } else {
                                it
                            }
                        }.toImmutableList(),
                    )
                }
                onNodeWithContentDescription(checklistLabel, useUnmergedTree = true).assertIsDisplayed()
            }
        }
    }

    @Test
    fun `split view shows a running session with its elapsed time`() = runSkikoComposeUiTest(size = Size(1440f, 900f)) {
        val running = messagesOf("s-handoff") + listOf(
            MessageUi.Prompt("p-run", now, "Добавь отчёт о переносе в сессию"),
            MessageUi.Reply(
                id = "r-run",
                createdAt = now,
                text = "Изучаю задачу в heartbeat. ",
                tools = persistentListOf(
                    ToolUi("t", "Выполняется поиск по проекту", ToolStatusUi.Running, "$ git status --short", null),
                ),
                isStreaming = true,
            ),
        )
        val state = workspace.copy(
            panes = persistentListOf(PaneUi(0, sessionId = "s-facade"), PaneUi(1, sessionId = "s-handoff")),
            focusedPaneId = 1,
            running = persistentSetOf("s-handoff"),
            runStartedAt = persistentMapOf("s-handoff" to now),
            transcripts = persistentMapOf(
                "s-facade" to messagesOf("s-facade"),
                "s-handoff" to running.toImmutableList(),
            ),
            now = now + 313.seconds,
        )
        setContent { HbTheme(darkTheme = false) { AiStudioContent(state, {}, exits) } }
        onNodeWithTag("pane-0").assertIsDisplayed()
        onNodeWithTag("pane-1").assertIsDisplayed()
        onNodeWithTag("run-status").assertIsDisplayed()
        save("studio-split-running", captureToImage().toAwtImage())
    }

    @Test
    fun `compact windows keep one pane and open the sidebar as a drawer`() =
        runSkikoComposeUiTest(size = Size(400f, 820f)) {
            val events = mutableListOf<AiStudioScreenIntent>()
            var state by mutableStateOf(
                workspace.copy(
                    panes = persistentListOf(PaneUi(0, sessionId = "s-build"), PaneUi(1)),
                    transcripts = transcriptsOf("s-build"),
                ),
            )
            setContent {
                HbTheme(darkTheme = false) {
                    AiStudioContent(state, {
                        events += it
                        if (it is AiStudioScreenIntent.SetDrawerOpen) {
                            state = state.copy(sidebar = state.sidebar.copy(isDrawerOpen = it.isOpen))
                        }
                    }, exits)
                }
            }
            onNodeWithTag("pane-1").assertDoesNotExist()
            onNodeWithTag("studio-rail").assertDoesNotExist()
            save("studio-compact", captureToImage().toAwtImage())
            onNodeWithTag("pane-open-sidebar").performClick()
            onNodeWithTag("studio-drawer").assertIsDisplayed()
            save("studio-compact-drawer", captureToImage().toAwtImage())
            onNodeWithTag("studio-scrim").performMouseInput { click(centerRight) }
            onNodeWithTag("studio-drawer").assertDoesNotExist()
            assertTrue(events.contains(AiStudioScreenIntent.SetDrawerOpen(false)))
        }

    @Test
    fun `hovering a session reveals its actions`() = runSkikoComposeUiTest(size = Size(1280f, 900f)) {
        val events = mutableListOf<AiStudioScreenIntent>()
        val state = workspace.copy(
            panes = persistentListOf(PaneUi(0, sessionId = "s-facade")),
            transcripts = transcriptsOf("s-facade"),
        )
        setContent { HbTheme(darkTheme = true) { AiStudioContent(state, events::add, exits) } }
        onNodeWithTag("transcript-s-facade").assertIsDisplayed()
        save("studio-dark", captureToImage().toAwtImage())
        onAllNodesWithTag("session-s-adr").onFirst().performMouseInput { moveTo(center) }
        onNodeWithTag("session-menu-project:s-adr", useUnmergedTree = true).performClick()
        save("studio-session-menu-dark", captureToImage().toAwtImage())
        onNodeWithTag("session-menu-project:s-adr", useUnmergedTree = true).performClick()
        onNodeWithTag("rail-archive").performClick()
        assertTrue(events.contains(AiStudioScreenIntent.ShowSidebarMode(SidebarMode.Archive)))
    }

    @Test
    fun `medium windows use a drawer before the sidebar can squeeze the conversation`() =
        runSkikoComposeUiTest(size = Size(719f, 900f)) {
            var state by mutableStateOf(
                workspace.copy(
                    panes = persistentListOf(PaneUi(0, sessionId = "s-facade")),
                    transcripts = transcriptsOf("s-facade"),
                    sidebar = workspace.sidebar.copy(isVisible = false),
                ),
            )
            setContent { HbTheme(darkTheme = false) { AiStudioContent(state, {}, exits) } }
            onNodeWithTag("studio-rail").assertDoesNotExist()
            onNodeWithTag("studio-sidebar").assertDoesNotExist()
            onNodeWithTag("pane-open-sidebar").assertIsDisplayed()
            onNodeWithTag("transcript-s-facade").assertIsDisplayed()
            onNodeWithTag("composer-0").assertIsDisplayed()
            save("studio-medium", captureToImage().toAwtImage())
            runOnIdle { state = state.copy(sidebar = state.sidebar.copy(isVisible = true)) }
            onNodeWithTag("studio-rail").assertDoesNotExist()
            onNodeWithTag("pane-open-sidebar").assertIsDisplayed()
        }

    private fun messagesOf(id: String) = seed.messages.getValue(id).map { it.toUi() }.toImmutableList()

    private fun transcriptsOf(id: String) = persistentMapOf(id to messagesOf(id))

    private fun save(name: String, image: java.awt.image.BufferedImage) {
        val file = File("build/reports/ai-studio/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }
}
