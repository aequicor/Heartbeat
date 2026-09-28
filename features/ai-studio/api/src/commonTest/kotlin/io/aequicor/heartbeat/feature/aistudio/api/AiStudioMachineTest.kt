package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class AiStudioMachineTest {
    private val settings = RunSettings("pulse", ReasoningEffort.High, ApprovalMode.Ask)
    private val defaults = StudioDefaults(projectId = "heartbeat", settings = settings)
    private val home = AiStudioState.Ready(
        panes = listOf(StudioPane(0, projectId = "heartbeat")),
        focusedPaneId = 0,
        settings = settings,
        defaultProjectId = "heartbeat",
    )
    private val session = home.copy(panes = listOf(StudioPane(0, sessionId = "s1")))
    private val split = home.copy(
        panes = listOf(StudioPane(0, sessionId = "s1"), StudioPane(1, sessionId = "s2")),
        focusedPaneId = 1,
    )

    @Test
    fun `start loads the workspace`() {
        AiStudioMachineSpec.assertTransition(
            from = AiStudioState.Idle,
            intent = AiStudioIntent.Public.Start,
            to = AiStudioState.Loading,
            effects = listOf(AiStudioEffect.Load),
        )
    }

    @Test
    fun `enabled workspace opens a new session page of the default project`() {
        AiStudioMachineSpec.assertTransition(
            from = AiStudioState.Loading,
            intent = AiStudioIntent.Internal.Loaded(isEnabled = true, defaults = defaults),
            to = home,
            effects = listOf(
                AiStudioEffect.ObserveAvailability,
                AiStudioEffect.ObserveRuntime,
                AiStudioEffect.ObserveModels,
                AiStudioEffect.ObserveProjects,
            ),
        )
    }

    @Test
    fun `switching the workspace toggle moves between the workspace and the placeholder`() {
        AiStudioMachineSpec.assertTransition(
            from = session.copy(running = setOf("s1")),
            intent = AiStudioIntent.Internal.AvailabilityChanged(isEnabled = false),
            to = AiStudioState.Disabled,
            effects = listOf(AiStudioEffect.ObserveAvailability),
        )
        AiStudioMachineSpec.assertTransition(
            from = AiStudioState.Disabled,
            intent = AiStudioIntent.Internal.AvailabilityChanged(isEnabled = true),
            to = AiStudioState.Loading,
            effects = listOf(AiStudioEffect.Load),
        )
        AiStudioMachineSpec.assertTransition(
            from = home,
            intent = AiStudioIntent.Internal.AvailabilityChanged(isEnabled = true),
            to = home,
        )
        AiStudioMachineSpec.assertTransition(
            from = AiStudioState.Disabled,
            intent = AiStudioIntent.Internal.AvailabilityChanged(isEnabled = false),
            to = AiStudioState.Disabled,
        )
    }

    @Test
    fun `failed effects release the workflow instead of leaving it waiting`() {
        val error = IllegalStateException("storage unavailable")
        assertEquals(
            AiStudioIntent.Internal.LoadFailed,
            AiStudioMachineSpec.onEffectFailure(AiStudioEffect.Load, error),
        )
        assertEquals(
            AiStudioIntent.Internal.CreateFailed(0, "Design"),
            AiStudioMachineSpec.onEffectFailure(AiStudioEffect.CreateSession(0, "p", "Design", settings), error),
        )
        assertEquals(
            AiStudioIntent.Internal.RunFinished("s1", RunOutcome.Failed),
            AiStudioMachineSpec.onEffectFailure(AiStudioEffect.Run("s1", "Next", settings), error),
        )
        assertEquals(
            AiStudioIntent.Internal.CancelFailed("s1"),
            AiStudioMachineSpec.onEffectFailure(AiStudioEffect.Cancel("s1"), error),
        )
        assertEquals(
            null,
            AiStudioMachineSpec.onEffectFailure(AiStudioEffect.Apply("s1", SessionEdit.SetPinned(true)), error),
        )
        assertEquals(
            AiStudioIntent.Internal.RuntimeLost,
            AiStudioMachineSpec.onEffectFailure(AiStudioEffect.ObserveRuntime, error),
        )
    }

    @Test
    fun `lost runtime observation clears every run, stop and permission`() {
        val permission = StudioPermission("s1", "request", "Allow", listOf(StudioPermissionOption("once", "Once")))
        AiStudioMachineSpec.assertTransition(
            from = session.copy(
                running = setOf("s1"),
                observedRunning = setOf("s1"),
                runStartedAt = mapOf("s1" to Instant.fromEpochSeconds(100)),
                stopping = setOf("s1"),
                stopFailures = setOf("s1"),
                permissions = listOf(permission),
                answeredPermissions = setOf("other"),
            ),
            intent = AiStudioIntent.Internal.RuntimeLost,
            to = session,
        )
    }

    @Test
    fun `runtime start times survive reopening and are cleared when the run finishes`() {
        val starts = mapOf("s1" to Instant.fromEpochSeconds(100))
        val running = session.copy(running = setOf("s1"), observedRunning = setOf("s1"), runStartedAt = starts)
        AiStudioMachineSpec.assertTransition(
            from = session,
            intent = AiStudioIntent.Internal.RuntimeChanged(
                StudioRuntimeState(running = setOf("s1"), runStartedAt = starts),
            ),
            to = running,
        )
        AiStudioMachineSpec.assertTransition(
            from = running,
            intent = AiStudioIntent.Internal.RuntimeChanged(StudioRuntimeState(runStartedAt = starts)),
            to = session,
        )
    }

    @Test
    fun `offered models fill only an empty model choice`() {
        val unset = home.copy(settings = settings.copy(modelId = ""))
        AiStudioMachineSpec.assertTransition(
            from = unset,
            intent = AiStudioIntent.Internal.ModelsChanged(listOf("route-a", "route-b")),
            to = unset.copy(settings = settings.copy(modelId = "route-a")),
        )
        AiStudioMachineSpec.assertTransition(
            from = home,
            intent = AiStudioIntent.Internal.ModelsChanged(listOf("route-a")),
            to = home,
        )
        AiStudioMachineSpec.assertTransition(
            from = unset,
            intent = AiStudioIntent.Internal.ModelsChanged(emptyList()),
            to = unset,
        )
    }

    @Test
    fun `disabled workspace shows the placeholder and failures can be retried`() {
        AiStudioMachineSpec.assertTransition(
            from = AiStudioState.Loading,
            intent = AiStudioIntent.Internal.Loaded(isEnabled = false, defaults = defaults),
            to = AiStudioState.Disabled,
            effects = listOf(AiStudioEffect.ObserveAvailability),
        )
        AiStudioMachineSpec.assertTransition(
            from = AiStudioState.Loading,
            intent = AiStudioIntent.Internal.LoadFailed,
            to = AiStudioState.LoadError,
        )
        AiStudioMachineSpec.assertTransition(
            from = AiStudioState.LoadError,
            intent = AiStudioIntent.Public.Retry,
            to = AiStudioState.Loading,
            effects = listOf(AiStudioEffect.Load),
        )
        AiStudioMachineSpec.assertIgnored(AiStudioState.Disabled, AiStudioIntent.Public.Retry)
        AiStudioMachineSpec.assertIgnored(AiStudioState.Idle, AiStudioIntent.Public.Submit(0, "hi"))
    }

    @Test
    fun `opening a session marks it read and focuses a pane that already shows it`() {
        AiStudioMachineSpec.assertTransition(
            from = home,
            intent = AiStudioIntent.Public.OpenSession("s1"),
            to = session,
            effects = listOf(AiStudioEffect.Apply("s1", SessionEdit.SetUnread(false))),
        )
        AiStudioMachineSpec.assertTransition(
            from = split,
            intent = AiStudioIntent.Public.OpenSession("s1"),
            to = split.copy(focusedPaneId = 0),
            effects = listOf(AiStudioEffect.Apply("s1", SessionEdit.SetUnread(false))),
        )
        AiStudioMachineSpec.assertIgnored(home, AiStudioIntent.Public.OpenSession("s1", paneId = 7))
    }

    @Test
    fun `new session page replaces the pane content and keeps the chosen project`() {
        AiStudioMachineSpec.assertTransition(
            from = session,
            intent = AiStudioIntent.Public.NewSession(projectId = null),
            to = home.copy(panes = listOf(StudioPane(0))),
        )
        AiStudioMachineSpec.assertTransition(
            from = home,
            intent = AiStudioIntent.Public.SelectProject(0, "site"),
            to = home.copy(panes = listOf(StudioPane(0, projectId = "site"))),
        )
        AiStudioMachineSpec.assertIgnored(session, AiStudioIntent.Public.SelectProject(0, "site"))
    }

    @Test
    fun `open beside adds a second pane and then reuses the unfocused one`() {
        AiStudioMachineSpec.assertTransition(
            from = session,
            intent = AiStudioIntent.Public.OpenBeside("s2"),
            to = split,
            effects = listOf(AiStudioEffect.Apply("s2", SessionEdit.SetUnread(false))),
        )
        AiStudioMachineSpec.assertTransition(
            from = split,
            intent = AiStudioIntent.Public.OpenBeside(null),
            to = split.copy(
                panes = listOf(StudioPane(0, projectId = "heartbeat"), StudioPane(1, sessionId = "s2")),
                focusedPaneId = 0,
            ),
        )
    }

    @Test
    fun `closing a focused pane focuses the remaining one and the last pane stays`() {
        AiStudioMachineSpec.assertTransition(
            from = split,
            intent = AiStudioIntent.Public.ClosePane(1),
            to = session,
        )
        AiStudioMachineSpec.assertIgnored(session, AiStudioIntent.Public.ClosePane(0))
        AiStudioMachineSpec.assertTransition(
            from = split,
            intent = AiStudioIntent.Public.FocusPane(0),
            to = split.copy(focusedPaneId = 0),
        )
        AiStudioMachineSpec.assertIgnored(split, AiStudioIntent.Public.FocusPane(1))
    }

    @Test
    fun `first prompt creates a session for the page project`() {
        AiStudioMachineSpec.assertTransition(
            from = home,
            intent = AiStudioIntent.Public.Submit(0, "  Design the facade "),
            to = home.copy(panes = listOf(StudioPane(0, projectId = "heartbeat", isCreating = true))),
            effects = listOf(AiStudioEffect.CreateSession(0, "heartbeat", "Design the facade", settings)),
        )
        AiStudioMachineSpec.assertIgnored(home, AiStudioIntent.Public.Submit(0, "   "))
        val creating = home.copy(panes = listOf(StudioPane(0, projectId = "heartbeat", isCreating = true)))
        AiStudioMachineSpec.assertIgnored(creating, AiStudioIntent.Public.Submit(0, "again"))
    }

    @Test
    fun `created session is shown in its pane and starts running`() {
        val creating = home.copy(panes = listOf(StudioPane(0, projectId = "heartbeat", isCreating = true)))
        AiStudioMachineSpec.assertTransition(
            from = creating,
            intent = AiStudioIntent.Internal.SessionCreated(0, "s9", "Design", settings),
            to = home.copy(panes = listOf(StudioPane(0, sessionId = "s9")), running = setOf("s9")),
            effects = listOf(AiStudioEffect.Run("s9", "Design", settings)),
        )
        AiStudioMachineSpec.assertTransition(
            from = creating,
            intent = AiStudioIntent.Internal.CreateFailed(0, "Design"),
            to = home,
            outputs = listOf(AiStudioOutput.SubmitFailed(0, "Design")),
        )
    }

    @Test
    fun `follow up prompt runs an idle session with current settings and busy sessions reject it`() {
        val fast = settings.copy(effort = ReasoningEffort.Low)
        AiStudioMachineSpec.assertTransition(
            from = session,
            intent = AiStudioIntent.Public.UpdateSettings(fast),
            to = session.copy(settings = fast),
        )
        AiStudioMachineSpec.assertTransition(
            from = session,
            intent = AiStudioIntent.Public.Submit(0, "Next"),
            to = session.copy(running = setOf("s1")),
            effects = listOf(AiStudioEffect.Run("s1", "Next", settings)),
        )
        AiStudioMachineSpec.assertIgnored(session.copy(running = setOf("s1")), AiStudioIntent.Public.Submit(0, "More"))
    }

    @Test
    fun `native effort preferences are carried into the next accepted run`() {
        val native = settings.copy(engineEfforts = mapOf(settings.modelId to "future-effort"))
        val configured = session.copy(settings = native)
        AiStudioMachineSpec.assertTransition(
            from = session,
            intent = AiStudioIntent.Public.UpdateSettings(native),
            to = configured,
        )
        AiStudioMachineSpec.assertTransition(
            from = configured,
            intent = AiStudioIntent.Public.Submit(0, "Next"),
            to = configured.copy(running = setOf("s1")),
            effects = listOf(AiStudioEffect.Run("s1", "Next", native)),
        )
    }

    @Test
    fun `stop is requested once and the finished run clears both flags`() {
        val running = session.copy(running = setOf("s1"))
        AiStudioMachineSpec.assertTransition(
            from = running,
            intent = AiStudioIntent.Public.Stop("s1"),
            to = running.copy(stopping = setOf("s1")),
            effects = listOf(AiStudioEffect.Cancel("s1")),
        )
        AiStudioMachineSpec.assertIgnored(running.copy(stopping = setOf("s1")), AiStudioIntent.Public.Stop("s1"))
        AiStudioMachineSpec.assertIgnored(session, AiStudioIntent.Public.Stop("s1"))
        AiStudioMachineSpec.assertTransition(
            from = running.copy(stopping = setOf("s1")),
            intent = AiStudioIntent.Internal.RuntimeChanged(StudioRuntimeState()),
            to = session,
        )
    }

    @Test
    fun `a run finishing out of sight marks its session unread`() {
        AiStudioMachineSpec.assertTransition(
            from = home.copy(running = setOf("s3")),
            intent = AiStudioIntent.Internal.RunFinished("s3", RunOutcome.Completed),
            to = home,
            effects = listOf(AiStudioEffect.Apply("s3", SessionEdit.SetUnread(true))),
            outputs = listOf(AiStudioOutput.RunEnded("s3", RunOutcome.Completed)),
        )
    }

    @Test
    fun `unsupported native cancellation is not offered as a stop operation`() {
        AiStudioMachineSpec.assertIgnored(
            session.copy(running = setOf("s1"), uncancellable = setOf("s1")),
            AiStudioIntent.Public.Stop("s1"),
        )
    }

    @Test
    fun `first prompt uses settings captured before asynchronous chat creation`() {
        val creating = home.copy(
            settings = settings.copy(modelId = "another-route"),
            panes = listOf(StudioPane(0, isCreating = true)),
        )
        AiStudioMachineSpec.assertTransition(
            from = creating,
            intent = AiStudioIntent.Internal.SessionCreated(0, "s1", "First prompt", settings),
            to = creating.copy(panes = listOf(StudioPane(0, sessionId = "s1")), running = setOf("s1")),
            effects = listOf(AiStudioEffect.Run("s1", "First prompt", settings)),
        )
    }

    @Test
    fun `runtime restoration exposes only current permission choices`() {
        val permission = StudioPermission(
            "s1",
            "request",
            "Allow action",
            listOf(StudioPermissionOption("once", "Once")),
        )
        val live = session.copy(running = setOf("s1"), observedRunning = setOf("s1"), permissions = listOf(permission))
        val answered = live.copy(permissions = emptyList(), answeredPermissions = setOf("request"))
        AiStudioMachineSpec.assertTransition(
            from = session,
            intent = AiStudioIntent.Internal.RuntimeChanged(StudioRuntimeState(setOf("s1"), listOf(permission))),
            to = live,
        )
        AiStudioMachineSpec.assertTransition(
            from = live,
            intent = AiStudioIntent.Public.RespondPermission("s1", "request", "once"),
            to = answered,
            effects = listOf(AiStudioEffect.RespondPermission("s1", "request", "once")),
        )
        AiStudioMachineSpec.assertIgnored(answered, AiStudioIntent.Public.RespondPermission("s1", "request", "once"))
        AiStudioMachineSpec.assertIgnored(
            answered.copy(permissions = listOf(permission)),
            AiStudioIntent.Public.RespondPermission("s1", "request", "once"),
        )
        AiStudioMachineSpec.assertTransition(
            from = answered,
            intent = AiStudioIntent.Internal.RuntimeChanged(StudioRuntimeState(setOf("s1"), listOf(permission))),
            to = answered,
        )
        AiStudioMachineSpec.assertTransition(
            from = answered,
            intent = AiStudioIntent.Internal.RuntimeChanged(StudioRuntimeState(setOf("s1"))),
            to = live.copy(permissions = emptyList()),
        )
        AiStudioMachineSpec.assertTransition(
            from = answered,
            intent = AiStudioIntent.Internal.PermissionAnswerFailed("s1", "request"),
            to = answered.copy(answeredPermissions = emptySet()),
            outputs = listOf(AiStudioOutput.PermissionAnswerFailed("s1", "request")),
        )
        AiStudioMachineSpec.assertTransition(
            from = answered.copy(answeredPermissions = emptySet()),
            intent = AiStudioIntent.Internal.RuntimeChanged(StudioRuntimeState(setOf("s1"), listOf(permission))),
            to = live,
        )
        AiStudioMachineSpec.assertIgnored(live, AiStudioIntent.Public.RespondPermission("s1", "request", "always"))
        AiStudioMachineSpec.assertIgnored(session, AiStudioIntent.Public.RespondPermission("s1", "request", "once"))
    }

    @Test
    fun `structured permission answers reach the effect`() {
        val permission = StudioPermission(
            "s1",
            "request",
            "Pick",
            listOf(StudioPermissionOption("submit", "Submit")),
            input = StudioPermissionInput.FreeText(null, false),
        )
        val live = session.copy(running = setOf("s1"), observedRunning = setOf("s1"), permissions = listOf(permission))
        val answer = StudioPermissionAnswer.Text("blue")
        AiStudioMachineSpec.assertTransition(
            from = live,
            intent = AiStudioIntent.Public.RespondPermission("s1", "request", "submit", answer),
            to = live.copy(permissions = emptyList(), answeredPermissions = setOf("request")),
            effects = listOf(AiStudioEffect.RespondPermission("s1", "request", "submit", answer)),
        )
    }

    @Test
    fun `follow-up answers run idle sessions only`() {
        AiStudioMachineSpec.assertTransition(
            from = session,
            intent = AiStudioIntent.Public.FollowUp("s1", " Pick\nblue "),
            to = session.copy(running = setOf("s1")),
            effects = listOf(AiStudioEffect.Run("s1", "Pick\nblue", session.settings)),
        )
        AiStudioMachineSpec.assertIgnored(
            session.copy(running = setOf("s1")),
            AiStudioIntent.Public.FollowUp("s1", "x"),
        )
        AiStudioMachineSpec.assertIgnored(session, AiStudioIntent.Public.FollowUp("s1", " "))
    }

    @Test
    fun `late run completion never clears a newer native run`() {
        val observed = session.copy(running = setOf("s1"), observedRunning = setOf("s1"))
        AiStudioMachineSpec.assertTransition(
            from = observed,
            intent = AiStudioIntent.Internal.RunFinished("s1", RunOutcome.Completed),
            to = observed,
            outputs = listOf(AiStudioOutput.RunEnded("s1", RunOutcome.Completed)),
        )
    }

    @Test
    fun `run completion clears a run the runtime no longer reports`() {
        AiStudioMachineSpec.assertTransition(
            from = session.copy(running = setOf("s1"), stopping = setOf("s1")),
            intent = AiStudioIntent.Internal.RunFinished("s1", RunOutcome.Stopped),
            to = session,
            outputs = listOf(AiStudioOutput.RunEnded("s1", RunOutcome.Stopped)),
        )
    }

    @Test
    fun `deferred native stop failure enables retry after early stop returned`() {
        val running = session.copy(running = setOf("s1"), stopping = setOf("s1"))
        AiStudioMachineSpec.assertTransition(
            from = running,
            intent = AiStudioIntent.Internal.RuntimeChanged(
                StudioRuntimeState(running = setOf("s1"), stopFailures = setOf("s1")),
            ),
            to = running.copy(stopping = emptySet(), stopFailures = setOf("s1"), observedRunning = setOf("s1")),
        )
    }

    @Test
    fun `failed stop preserves execution and enables another attempt`() {
        val running = session.copy(running = setOf("s1"), stopping = setOf("s1"))
        AiStudioMachineSpec.assertTransition(
            from = running,
            intent = AiStudioIntent.Internal.CancelFailed("s1"),
            to = running.copy(stopping = emptySet(), stopFailures = setOf("s1")),
        )
    }

    @Test
    fun `edits are persisted and archiving moves open panes to the default project`() {
        AiStudioMachineSpec.assertTransition(
            from = split,
            intent = AiStudioIntent.Public.Edit("s1", SessionEdit.Rename("  Facade  ")),
            to = split,
            effects = listOf(AiStudioEffect.Apply("s1", SessionEdit.Rename("Facade"))),
        )
        AiStudioMachineSpec.assertIgnored(split, AiStudioIntent.Public.Edit("s1", SessionEdit.Rename(" ")))
        val resolution = AiStudioMachineSpec.assertTransition(
            from = split,
            intent = AiStudioIntent.Public.Edit("s2", SessionEdit.SetArchived(true)),
            to = split.copy(panes = listOf(StudioPane(0, sessionId = "s1"), StudioPane(1, projectId = "heartbeat"))),
            effects = listOf(AiStudioEffect.Apply("s2", SessionEdit.SetArchived(true))),
        )
        assertEquals(1, (resolution.to as AiStudioState.Ready).focusedPaneId)
    }
}
