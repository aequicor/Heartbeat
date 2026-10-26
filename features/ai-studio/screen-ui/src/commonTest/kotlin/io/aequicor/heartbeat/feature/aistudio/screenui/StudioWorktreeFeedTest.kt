package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.ui.platform.UriHandler
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbMessagePart
import io.aequicor.heartbeat.ds.components.HbToolBlock
import io.aequicor.heartbeat.ds.components.HbToolCall
import io.aequicor.heartbeat.ds.components.HbToolKind
import io.aequicor.heartbeat.ds.components.HbToolStatus
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.BuildPhaseUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.BuildUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.MessageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SandboxPhaseUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ToolUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeActionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeJournalUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreePhaseUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.WorktreeUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

class StudioWorktreeFeedTest {
    @Test
    fun `task phases map to tool statuses with titles from the worktree copy`() {
        val expected = mapOf(
            WorktreePhaseUi.Preparing to (HbToolStatus.Running to "Preparing"),
            WorktreePhaseUi.Working to (HbToolStatus.Running to "Worktree"),
            WorktreePhaseUi.CompletionSignaled to (HbToolStatus.Running to "Worktree"),
            WorktreePhaseUi.ActionWorking to (HbToolStatus.Running to "Applying action"),
            WorktreePhaseUi.AwaitingDecision to (HbToolStatus.Complete to "Task completed"),
            WorktreePhaseUi.RecoveryRequired to (HbToolStatus.Error to "Needs reconciliation"),
            WorktreePhaseUi.Failed to (HbToolStatus.Error to "Operation failed"),
        )
        expected.forEach { (phase, card) ->
            val message = timeline(task(phase)).messages.single()
            val call = assertIs<HbMessagePart.Tool>(message.parts.single()).call
            assertEquals("worktree:chat", message.id)
            assertEquals("worktree:chat", call.id)
            assertEquals(HbChatRole.System, message.role)
            assertEquals(HbToolKind.Worktree, call.kind)
            assertEquals(card, call.status to call.title, "phase $phase")
        }
        assertTrue(timeline(task(WorktreePhaseUi.Idle)).messages.isEmpty())
        assertTrue(timeline(task(WorktreePhaseUi.Retained)).messages.isEmpty())
    }

    @Test
    fun `completed task keeps its summary in view and offers every decision with the store intents`() {
        val result = timeline(task(WorktreePhaseUi.AwaitingDecision, summary = "  All tests pass\n"))
        val call = result.card("worktree:chat")
        assertEquals("All tests pass\nMerge target: master", call.summary)
        assertTrue(call.blocks.isEmpty())
        assertEquals(
            listOf("Create PR", "Merge into master", "Refine", "Leave"),
            call.actions.map { it.label },
        )
        assertEquals(HbButtonStyle.Primary, call.actions.first().style)
        WorktreeActionUi.entries.filter { it != WorktreeActionUi.HandOff }.forEach { action ->
            assertEquals(
                StudioIntent.DecideWorktree("chat", action),
                result.press("worktree:chat", "worktree-action-${action.name}"),
            )
        }

        val detached = timeline(task(WorktreePhaseUi.AwaitingDecision, sourceBranch = null)).card("worktree:chat")
        assertEquals("heartbeat/task", detached.summary)
        assertTrue(detached.actions.none { it.id == "worktree-action-Merge" })
    }

    @Test
    fun `a paused sandbox asks with its own card so it lands next to the composer`() {
        val conflicted = task(WorktreePhaseUi.Working)
            .copy(sandboxPhase = SandboxPhaseUi.Conflicted, sandboxConflicts = persistentListOf("a.kt"))
        val result = timeline(conflicted)
        assertEquals(listOf("worktree:chat:paused"), result.messages.map { it.id })
        assertTrue(result.card("worktree:chat:paused").actions.any { it.id == "sandbox-continue" })
    }

    @Test
    fun `sandbox cards address the worktree task and map every pause phase`() {
        val cell = task(WorktreePhaseUi.Working).copy(taskId = "organism.chat.cell")
        val waiting = timeline(cell.copy(sandboxPhase = SandboxPhaseUi.Waiting))
        assertEquals(
            StudioIntent.RecheckWorktree("organism.chat.cell", isResume = true),
            waiting.press("worktree:chat:paused", SANDBOX_CONTINUE_ID),
        )
        assertEquals(HbToolStatus.Pending, waiting.card("worktree:chat:paused").status)
        listOf(SandboxPhaseUi.Pausing, SandboxPhaseUi.Rebasing).forEach { phase ->
            val busy = timeline(cell.copy(sandboxPhase = phase)).card("worktree:chat:paused")
            assertEquals(HbToolStatus.Running, busy.status)
            assertTrue(busy.actions.isEmpty())
        }
        val conflicts = (1..12).map { "f$it.kt" }
        val conflicted = timeline(
            cell.copy(
                sandboxPhase = SandboxPhaseUi.Conflicted,
                sandboxConflicts = persistentListOf<String>().addAll(conflicts),
            ),
        )
        val card = conflicted.card("worktree:chat:paused")
        assertEquals((conflicts.take(10) + "+2").joinToString("\n"), card.summary)
        assertEquals(listOf(SANDBOX_CONTINUE_ID, SANDBOX_LEAVE_PAUSED_ID), card.actions.map { it.id })
        assertEquals(
            StudioIntent.RecheckWorktree("organism.chat.cell"),
            conflicted.press("worktree:chat:paused", SANDBOX_LEAVE_PAUSED_ID),
        )
        val resolving = timeline(cell.copy(sandboxPhase = SandboxPhaseUi.Conflicted, isSandboxResolving = true))
        assertEquals(listOf("worktree:chat:paused"), resolving.messages.map { it.id })
        assertEquals(HbToolStatus.Running, resolving.card("worktree:chat:paused").status)
    }

    @Test
    fun `finished sandbox offers a branch hand off instead of a pull request or a merge`() {
        val sandbox = task(WorktreePhaseUi.AwaitingDecision).copy(sandboxPhase = SandboxPhaseUi.Ready)
        val call = timeline(sandbox).card("worktree:chat")
        assertEquals(listOf("Hand off to heartbeat/task", "Refine", "Leave"), call.actions.map { it.label })
        assertEquals(
            StudioIntent.DecideWorktree("chat", WorktreeActionUi.HandOff),
            timeline(sandbox).press("worktree:chat", "worktree-action-HandOff"),
        )
    }

    @Test
    fun `a handed off sandbox names its branch unless the branch was left unmoved`() {
        val handedOff = task(WorktreePhaseUi.Retained)
            .copy(sandboxPhase = SandboxPhaseUi.HandedOff, resultCommit = "abc123")
        val moved = timeline(handedOff).card("worktree:chat")
        assertEquals("Handed off" to "heartbeat/task\nabc123", moved.title to moved.summary)
        val unmoved = timeline(handedOff.copy(isBranchUnmoved = true)).card("worktree:chat")
        assertEquals("Branch unmoved" to "abc123", unmoved.title to unmoved.summary)
    }

    @Test
    fun `failures explain known codes without exposing raw ones and offer a recheck`() {
        val dirty = timeline(task(WorktreePhaseUi.RecoveryRequired, failure = "OriginalCheckoutDirty"))
        assertEquals("Checkout is dirty", dirty.card("worktree:chat").summary)
        assertEquals(StudioIntent.RecheckWorktree("chat"), dirty.press("worktree:chat", RECHECK_ID))
        val unknown = timeline(task(WorktreePhaseUi.Failed, failure = "UnexpectedInternalFailure"))
        assertEquals("Unknown failure", unknown.card("worktree:chat").summary)
        assertTrue(unknown.card("worktree:chat").actions.none { it.id.startsWith("worktree-action-") })
    }

    @Test
    fun `builds map their phases and keep a bounded tail of the failed output`() {
        val diagnostic = (0..99).joinToString("\n") { "Log line $it: ${"details ".repeat(8)}" } +
            "\nCompilation failed at Main.kt:42"
        val builds = BuildPhaseUi.entries.mapIndexed { index, phase ->
            BuildUi("b$index", "compile", phase, "> Task :compile\nfinished", null)
        }
        val calls = builds.windowed(3, 3, partialWindows = true).flatMap { recent ->
            timeline(task(WorktreePhaseUi.Idle, builds = recent)).messages.map {
                assertIs<HbMessagePart.Tool>(it.parts.single()).call
            }
        }
        val statuses = calls.map { it.status }
        assertEquals(
            listOf(
                HbToolStatus.Pending,
                HbToolStatus.Pending,
                HbToolStatus.Running,
                HbToolStatus.Complete,
                HbToolStatus.Cancelled,
                HbToolStatus.Error,
                HbToolStatus.Error,
            ),
            statuses,
        )
        assertEquals(
            listOf(BuildPhaseUi.Failed, BuildPhaseUi.Unknown),
            calls.filter { it.blocks.isNotEmpty() }.map { call -> builds.single { "build:${it.id}" == call.id }.phase },
        )

        val failed = BuildUi("failed", "compile", BuildPhaseUi.Failed, diagnostic, "Exit1")
        val queued = BuildUi("queued", "test", BuildPhaseUi.Queued, "", null, queuePosition = 2)
        val result = timeline(task(WorktreePhaseUi.Idle, builds = listOf(failed, queued)))
        assertEquals(listOf("build:failed", "build:queued"), result.messages.map { it.id })
        val log = result.card("build:failed")
        assertEquals("Exit1", log.summary)
        val tail = assertIs<HbToolBlock.Console>(log.blocks.single())
        assertEquals("build-output-failed", tail.id)
        assertTrue(tail.text.endsWith("Compilation failed at Main.kt:42"))
        assertTrue(tail.text.length <= 4000 && tail.text.lines().size <= 40)
        assertTrue(!tail.text.contains("Log line 0:"))
        assertTrue(log.actions.isEmpty())
        assertEquals("Queued · Position 2", result.card("build:queued").summary)
        assertEquals(
            StudioIntent.CancelWorktreeBuild("chat", "queued"),
            result.press("build:queued", "cancel-build-queued"),
        )
        assertNull(result.press("build:failed", "cancel-build-queued"))
    }

    @Test
    fun `only the three most recent builds follow the task`() {
        val builds = (1..5).map { BuildUi("b$it", "build $it", BuildPhaseUi.Completed, "", null) }
        assertEquals(
            listOf("build:b3", "build:b4", "build:b5"),
            timeline(task(WorktreePhaseUi.Idle, builds = builds)).messages.map { it.id },
        )
    }

    @Test
    fun `returning to a session refreshes previously shown builds outside the recent window`() {
        val running = BuildUi("first", "compile", BuildPhaseUi.Running, "", null)
        val initial = timeline(task(WorktreePhaseUi.Idle, builds = listOf(running)))
        val placement = TimelineWeaves()
        TimelineCache(placement).update(emptyList(), timelineLabels, initial.messages, initial.retained)
        assertEquals(HbToolStatus.Running, initial.card("build:first").status)
        val builds = listOf(running.copy(phase = BuildPhaseUi.Failed, output = "Compile failed", failure = "Exit1")) +
            (1..4).map { BuildUi("later$it", "test", BuildPhaseUi.Completed, "", null) }
        val feed = WorktreeFeed(
            "chat",
            "chat",
            task(WorktreePhaseUi.Idle, builds = builds),
            WorktreeJournalUi.Ready,
            isPreparing = false,
        )
        val current = worktreeTimeline(feed, labels, placement.cards.keys)
        val restored = TimelineCache(placement).update(emptyList(), timelineLabels, current.messages, current.retained)
        assertEquals(
            listOf("build:first", "build:later2", "build:later3", "build:later4"),
            restored.messages.map { it.id },
        )
        val call = assertIs<HbMessagePart.Tool>(restored.messages.first().parts.single()).call
        assertEquals(HbToolStatus.Error, call.status)
        assertEquals("Compile failed", assertIs<HbToolBlock.Console>(call.blocks.single()).text)
        assertTrue(call.actions.isEmpty())
        assertTrue(current.commands.isEmpty())

        val stillRunning = worktreeTimeline(
            feed.copy(task = task(WorktreePhaseUi.Idle, builds = listOf(running) + builds.drop(1))),
            labels,
            placement.cards.keys,
        )
        assertEquals(
            StudioIntent.CancelWorktreeBuild("chat", "first"),
            stillRunning.press("build:first", "cancel-build-first"),
        )
    }

    @Test
    fun `an inline build card stays offered after newer builds push it out of the recent window`() {
        val placement = TimelineWeaves()
        val cache = TimelineCache(placement)
        val prompt = MessageUi.Prompt("m1", Instant.fromEpochSeconds(0), "Build it")
        var builds = emptyList<BuildUi>()
        var calls = emptyList<ReplyPartUi>()
        var current = WorktreeTimeline()
        var shown = cache.update(emptyList(), timelineLabels)
        for (index in 1..4) {
            val phase = if (index == 1) BuildPhaseUi.Running else BuildPhaseUi.Completed
            builds = builds + BuildUi("b$index", "verify", phase, "", null)
            calls = calls + ReplyPartUi.Tool(
                ToolUi("t$index", "run_build", ToolStatusUi.Done, "{\"id\":\"b$index\"}", null),
            )
            val reply = MessageUi.Reply("m2", Instant.fromEpochSeconds(1), "", persistentListOf(), isStreaming = true)
                .copy(parts = calls.toImmutableList())
            val task = task(WorktreePhaseUi.Idle, builds = builds)
            val feed = WorktreeFeed("chat", "chat", task, WorktreeJournalUi.Ready, isPreparing = false)
            current = worktreeTimeline(feed, labels, placement.shown)
            shown = cache.update(listOf(prompt, reply), timelineLabels, current.messages, current.retained)
        }

        val parts = shown.messages.single { it.id == "m2" }.parts.map { assertIs<HbMessagePart.Tool>(it).call.id }
        assertEquals(listOf("t1", "build:b1", "t2", "build:b2", "t3", "build:b3", "t4", "build:b4"), parts)
        assertEquals(StudioIntent.CancelWorktreeBuild("chat", "b1"), current.press("build:b1", "cancel-build-b1"))
    }

    @Test
    fun `journal notices replace stale decisions and keep the verified pull request`() {
        val loading = timeline(task(WorktreePhaseUi.AwaitingDecision), WorktreeJournalUi.Loading)
        assertEquals(listOf(JOURNAL_LOADING_ID), loading.messages.map { it.id })
        assertEquals(HbToolStatus.Running, loading.card(JOURNAL_LOADING_ID).status)
        assertTrue(loading.commands.isEmpty())

        val withPr = task(WorktreePhaseUi.AwaitingDecision, pullRequestUrl = "https://example.test/pr/1")
        val build = BuildUi("running", "test", BuildPhaseUi.Running, "", null)
        val error = timeline(withPr.copy(builds = persistentListOf(build)), WorktreeJournalUi.Error)
        assertEquals(listOf(JOURNAL_ERROR_ID, "worktree:chat", "build:running"), error.messages.map { it.id })
        assertEquals(HbToolStatus.Error, error.card(JOURNAL_ERROR_ID).status)
        assertEquals(StudioIntent.RetryWorktreeJournal, error.press(JOURNAL_ERROR_ID, JOURNAL_RETRY_ID))
        val linked = error.card("worktree:chat")
        assertEquals(listOf(OPEN_PR_ID), linked.actions.map { it.id })
        assertEquals(HbToolStatus.Pending, linked.status)
        assertEquals("https://example.test/pr/1", error.press("worktree:chat", OPEN_PR_ID))
        assertTrue(error.card("build:running").actions.isEmpty())
    }

    @Test
    fun `retained task links its pull request`() {
        val call = timeline(task(WorktreePhaseUi.Retained, pullRequestUrl = "https://example.test/pr/2"))
            .card("worktree:chat")
        assertEquals(HbToolStatus.Complete, call.status)
        assertEquals(listOf(OPEN_PR_ID), call.actions.map { it.id })
    }

    @Test
    fun `a verified pull request stays linked while the task still runs`() {
        val running = listOf(
            WorktreePhaseUi.Preparing,
            WorktreePhaseUi.Working,
            WorktreePhaseUi.CompletionSignaled,
            WorktreePhaseUi.ActionWorking,
        )
        running.forEach { phase ->
            val result = timeline(task(phase, pullRequestUrl = "https://example.test/pr/4"))
            assertEquals(listOf(OPEN_PR_ID), result.card("worktree:chat").actions.map { it.id }, "phase $phase")
            assertEquals("https://example.test/pr/4", result.press("worktree:chat", OPEN_PR_ID))
        }
    }

    @Test
    fun `a failing browser never escapes the pull request action`() {
        val opened = mutableListOf<String>()
        openPullRequest(Links { opened += it }, "https://example.test/pr/5")
        assertEquals(listOf("https://example.test/pr/5"), opened)
        // Desktop AWT reports a missing browser through unsupported-action and checked I/O failures.
        listOf(UnsupportedOperationException("No browse action"), IllegalStateException("No activity"), Exception())
            .forEach { failure -> openPullRequest(Links { throw failure }, "https://example.test/pr/5") }
    }

    @Test
    fun `panes without worktree context add nothing to the transcript`() {
        val pane = PaneUi(0, sessionId = "chat")
        val plain = AiStudioScreenState(
            panes = persistentListOf(pane),
            sessions = persistentListOf(SessionUi("chat", "Feature", "project", Instant.DISTANT_PAST)),
        )
        assertNull(plain.paneContent(pane).worktreeFeed())
        assertEquals(WorktreeTimeline(), worktreeTimeline(null, labels))
        val worktreeSession = plain.copy(sessions = persistentListOf(plain.sessions.single().copy(isWorktree = true)))
        val unloaded = worktreeSession.paneContent(pane).worktreeFeed()
        assertEquals("chat", unloaded?.key)
        assertEquals(WorktreeTimeline(), worktreeTimeline(unloaded, labels))
    }

    @Test
    fun `a new worktree page shows only a failed journal that must be retried`() {
        val newPage = PaneUi(1, projectId = "project", isWorktree = true)
        val loading = AiStudioScreenState(
            panes = persistentListOf(newPage),
            worktreeJournal = WorktreeJournalUi.Loading,
        )
        assertNull(loading.paneContent(newPage).worktreeFeed())
        val failed = loading.copy(worktreeJournal = WorktreeJournalUi.Error).paneContent(newPage).worktreeFeed()
        assertEquals("pane-1", failed?.key)
        assertEquals(listOf(JOURNAL_ERROR_ID), worktreeTimeline(failed, labels).messages.map { it.id })
        assertNull(loading.paneContent(newPage.copy(isWorktree = false)).worktreeFeed())
    }

    @Test
    fun `a pane preparing a worktree session shows the preparation in its own feed`() {
        val pane = PaneUi(7, projectId = "project", isCreating = true, isWorktree = true)
        val feed = AiStudioScreenState(panes = persistentListOf(pane)).paneContent(pane).worktreeFeed()
        assertEquals("pane-7", feed?.key)
        val call = worktreeTimeline(feed, labels).card("worktree:pane-7")
        assertEquals(HbToolStatus.Running to "Preparing", call.status to call.title)
        val blocked = AiStudioScreenState(panes = persistentListOf(pane), worktreeJournal = WorktreeJournalUi.Error)
        assertEquals(
            listOf(JOURNAL_ERROR_ID, "worktree:pane-7"),
            worktreeTimeline(blocked.paneContent(pane).worktreeFeed(), labels).messages.map { it.id },
        )
    }

    @Test
    fun `every invoked build stays retained beyond the recent-build window`() {
        val builds = (1..5).map { BuildUi("b$it", "verify", BuildPhaseUi.Completed, "", failure = null) }
        val result = timeline(task(WorktreePhaseUi.Idle, builds = builds))

        assertEquals(builds.map { "build:${it.id}" }.toSet(), result.retained.toSet())
        assertEquals(listOf("build:b3", "build:b4", "build:b5"), result.messages.map { it.id })
    }

    @Test
    fun `dispatch sends store intents and opens verified links`() {
        val result = timeline(task(WorktreePhaseUi.Failed, pullRequestUrl = "https://example.test/pr/3"))
        val intents = mutableListOf<StudioIntent>()
        val links = mutableListOf<String>()
        listOf(RECHECK_ID, OPEN_PR_ID, "agent-tool-action").forEach {
            result.dispatch("worktree:chat", it, intents::add, links::add)
        }
        result.dispatch("build:other", RECHECK_ID, intents::add, links::add)
        assertEquals(listOf<StudioIntent>(StudioIntent.RecheckWorktree("chat")), intents)
        assertEquals(listOf("https://example.test/pr/3"), links)
    }

    private fun interface Links : UriHandler

    /** What pressing [actionId] on card [callId] does: the sent intent, the opened link or nothing. */
    private fun WorktreeTimeline.press(callId: String, actionId: String): Any? {
        var effect: Any? = null
        dispatch(callId, actionId, { effect = it }) { effect = it }
        return effect
    }

    private fun WorktreeTimeline.card(id: String): HbToolCall =
        assertIs<HbMessagePart.Tool>(messages.single { it.id == id }.parts.single()).call

    private fun timeline(task: WorktreeUi, journal: WorktreeJournalUi = WorktreeJournalUi.Ready): WorktreeTimeline =
        worktreeTimeline(WorktreeFeed("chat", "chat", task, journal, isPreparing = false), labels)

    private fun task(
        phase: WorktreePhaseUi,
        sourceBranch: String? = "master",
        summary: String? = null,
        pullRequestUrl: String? = null,
        failure: String? = null,
        builds: List<BuildUi> = emptyList(),
    ) = WorktreeUi(
        phase,
        "heartbeat/task",
        sourceBranch,
        summary,
        pullRequestUrl,
        failure,
        persistentListOf<BuildUi>().addAll(builds),
    )

    private val timelineLabels = TimelineLabels(
        section = "Session",
        you = "You",
        agent = "Agent",
        studio = "Studio",
        stoppedTemplate = "Stopped after %1\$s",
        failed = FailureLabels(unknown = "Failed", limit = "Limit"),
        durations = DurationLabels(secondsTemplate = "%1\$d s", minutesTemplate = "%1\$d min %2\$d s"),
        compacted = "Context compacted",
        rolledOver = "Continued in a new session",
        collapsedTools = "Tool calls: %1\$d",
    )

    private val labels = WorktreeLabels(
        author = "Studio",
        worktree = "Worktree",
        preparing = "Preparing",
        journalLoading = "Restoring",
        journalFailed = "Restore failed",
        journalRetry = "Retry",
        result = "Task completed",
        createPr = "Create PR",
        mergeTemplate = "Merge into %1\$s",
        mergeTargetTemplate = "Merge target: %1\$s",
        refine = "Refine",
        leave = "Leave",
        sandboxPaused = "Sandbox paused",
        sandboxConflicted = "Sandbox conflicts",
        sandboxContinue = "Continue",
        sandboxResolve = "Resolve",
        sandboxLeavePaused = "Leave paused",
        sandboxHandOffTemplate = "Hand off to %1\$s",
        sandboxHandedOff = "Handed off",
        sandboxHandedOffUnmoved = "Branch unmoved",
        openPr = "Open PR",
        actionWorking = "Applying action",
        failed = "Operation failed",
        recovery = "Needs reconciliation",
        recheck = "Check",
        builds = BuildLabels("Cancel", "Queued", "Waiting", "Unknown outcome", "Position %1\$d"),
        failures = WorktreeFailureLabels(
            checkoutDirty = "Checkout is dirty",
            branchChanged = "Branch changed",
            branchMoved = "Branch moved",
            branchUnavailable = "No branch",
            checkoutUnavailable = "No checkout",
            pullRequestBase = "No base",
            nativeUnknown = "Unknown result",
            actionDelivery = "Not delivered",
            mergeInProgress = "Merging",
            actionUnverified = "Unverified",
            unknown = "Unknown failure",
        ),
    )
}
