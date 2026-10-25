package io.aequicor.heartbeat.feature.worktreemode.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxPhase
import io.aequicor.heartbeat.feature.worktreemode.api.VirtualSandboxState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeAction
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildCommand
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunIdentity
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSandboxEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSeal
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorktreeJournalTest {
    @Test
    fun `pinned stages keep a physical sandbox and replay the same base when the toggle changes`() = runTest {
        val fixture = Fixture()
        fixture.git.isVirtualSupported = true
        fixture.toggles.isSandbox = true
        fixture.journal.load()
        val request = WorktreeIntent.Public.Prepare(CHAT, PROJECT, isSandbox = true, isBasePinned = true)
        fixture.send(request)
        val first = fixture.task()
        assertTrue(first.isSandbox)
        assertNull(first.virtualSandbox)
        assertEquals(first.baseCommit, first.pinnedBaseCommit)
        fixture.toggles.isSandbox = false
        fixture.send(request)
        assertEquals(first, fixture.task())
        assertEquals(first.pinnedBaseCommit, fixture.reopen().load()[CHAT]?.pinnedBaseCommit)
        assertFailsWith<IllegalStateException> { fixture.send(request.copy(baseCommit = "different")) }
        assertFailsWith<IllegalStateException> { fixture.send(request.copy(project = WorkspaceRef("other"))) }
    }

    @Test
    fun `a new pinned stage refuses a disabled sandbox without a plain worktree fallback`() = runTest {
        val fixture = Fixture()
        fixture.journal.load()
        assertFailsWith<IllegalStateException> {
            fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT, isSandbox = true, isBasePinned = true))
        }
        assertTrue(fixture.reopen().load().isEmpty())
    }

    @Test
    fun `repeated pinned seal gives a fresh durable acknowledgement of the same proof`() = runTest {
        val fixture = Fixture()
        fixture.toggles.isSandbox = true
        assertRepeatedPinnedSeal(fixture.journal, fixture::send, fixture::task, fixture::reopen)
    }

    @Test
    fun `completed virtual children keep reusable workspaces and immutable result snapshots`() = runTest {
        val fixture = Fixture()
        fixture.git.isVirtualSupported = true
        fixture.toggles.isSandbox = true
        fixture.journal.load()
        fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT, true, WorkspaceRef("parent")))
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, REQUEST))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, REQUEST, SESSION, TURN))
        fixture.send(WorktreeIntent.Public.TaskCompleteSignaled(CHAT, SESSION, TURN, "Done"))
        fixture.send(WorktreeIntent.Public.RunSettled(CHAT, REQUEST, SESSION, TURN, TurnOutcome.Completed))
        val result = fixture.task()
        assertEquals(0, fixture.git.handoffCount)
        assertEquals(VirtualSandboxPhase.Ready, result.virtualSandbox?.phase)
        assertEquals(result.seal?.commit, result.virtualResultSnapshots[REQUEST.value])
        assertEquals(1, fixture.git.sealed.size)
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("follow-up")))
        assertEquals(WorktreePhase.Working, fixture.task().phase)
        assertEquals(result.virtualResultSnapshots, fixture.task().virtualResultSnapshots)
    }

    @Test
    fun `completed virtual root hands its branch to the user only on explicit choice`() = runTest {
        val fixture = Fixture()
        fixture.git.isVirtualSupported = true
        fixture.toggles.isSandbox = true
        fixture.journal.load()
        fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT, true))
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, REQUEST))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, REQUEST, SESSION, TURN))
        fixture.send(WorktreeIntent.Public.TaskCompleteSignaled(CHAT, SESSION, TURN, "Done"))
        fixture.send(WorktreeIntent.Public.RunSettled(CHAT, REQUEST, SESSION, TURN, TurnOutcome.Completed))
        assertEquals(0, fixture.git.handoffCount)
        assertEquals(WorktreePhase.AwaitingDecision, fixture.task().phase)
        fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.HandOff))
        assertEquals(1, fixture.git.handoffCount)
        assertEquals(VirtualSandboxPhase.HandedOff, fixture.task().virtualSandbox?.phase)
        assertEquals(WorktreePhase.Retained, fixture.task().phase)
    }

    @Test
    fun `failure before Git planning is durable and retry recreates the checkout`() = runTest {
        val fixture = Fixture()
        val prepare = WorktreeIntent.Public.Prepare(CHAT, PROJECT)
        fixture.git.hasPlanFailure = true
        assertFailsWith<IllegalStateException> { fixture.send(prepare) }
        fixture.journal.failed(prepare, "GitProcessUnavailable")
        assertEquals("GitProcessUnavailable", fixture.reopen().load()[CHAT]?.failure)
        fixture.git.hasPlanFailure = false
        fixture.send(prepare)
        assertEquals(WorktreePhase.Idle, fixture.task().phase)
        assertEquals(CHECKOUT, fixture.task().executionWorkspace)
    }

    @Test
    fun `completion choice is claimed once and survives an ambiguous action handoff`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val action = WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.CreatePr)
        val first = async { fixture.send(action) }
        val second = async { fixture.send(action) }
        first.await()
        second.await()
        assertEquals(1, fixture.git.promptCount)
        val operation = checkNotNull(fixture.task().actionRequest).operation
        fixture.send(WorktreeIntent.Public.ActionDelivered(CHAT, operation))
        val restored = checkNotNull(fixture.reopen().load()[CHAT])
        assertEquals(WorktreePhase.RecoveryRequired, restored.phase)
        assertEquals(operation, restored.expectedAction?.operation)
        assertEquals(1, fixture.git.promptCount)
    }

    @Test
    fun `a coding request winning between choice read and atomic claim cannot launch an action`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.stores.store.writeStarted = entered
        fixture.stores.store.writeGate = proceed
        val metadata = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, WorktreeBuildPlan("test", emptyList())))
        }
        entered.await()
        // The first write queues choice-lock lookup; the second queues its snapshot read before RunStarted.
        val choice = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.Merge))
        }
        val secondEntered = CompletableDeferred<Unit>()
        val secondProceed = CompletableDeferred<Unit>()
        fixture.stores.store.writeStarted = secondEntered
        fixture.stores.store.writeGate = secondProceed
        val approval = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.ApproveBuildPlan(CHAT, fixture.task().revision + 1, false))
        }
        proceed.complete(Unit)
        secondEntered.await()
        // FIFO ownership now forces old choice read, new run commit, then the losing action claim.
        val next = RequestId("next")
        val coding = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.RunStarted(CHAT, next))
        }
        secondProceed.complete(Unit)
        metadata.await()
        approval.await()
        choice.await()
        coding.await()
        val task = checkNotNull(fixture.journal.taskProjection(CHAT))
        assertEquals(next, task.run?.request)
        assertEquals(WorktreePhase.Working, task.phase)
        assertNull(task.actionRequest)
        assertNull(task.expectedAction)
        assertEquals(0, fixture.git.preflightCount)
        assertEquals(0, fixture.git.promptCount)
        assertEquals(0, fixture.builds.acquireCount)
    }

    @Test
    fun `late action prompt or failure cannot overwrite a recovered replacement coding run`() = runTest {
        for (hasFailure in listOf(false, true)) {
            val fixture = Fixture()
            fixture.completeCoding()
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            fixture.git.promptStarted = entered
            fixture.git.promptGate = proceed
            fixture.git.hasPromptFailure = hasFailure
            val choice = async {
                fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.CreatePr))
            }
            entered.await()
            fixture.journal.failed(WorktreeIntent.Public.Recheck(CHAT), "OriginalCheckoutDirty")
            fixture.send(WorktreeIntent.Public.Recheck(CHAT))
            val next = RequestId("next")
            fixture.send(WorktreeIntent.Public.RunStarted(CHAT, next))
            proceed.complete(Unit)
            choice.await()
            val task = checkNotNull(fixture.journal.taskProjection(CHAT))
            assertEquals(next, task.run?.request)
            assertEquals(WorktreePhase.Working, task.phase)
            assertNull(task.actionRequest)
            assertNull(task.expectedAction)
            assertNull(task.failure)
            assertEquals(1, fixture.git.promptCount)
        }
    }

    @Test
    fun `recovery superseding a waiting merge claim releases its lease without delivering a prompt`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.builds.leaseStarted = entered
        fixture.builds.leaseGate = proceed
        val choice = async { fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.Merge)) }
        entered.await()
        fixture.journal.failed(WorktreeIntent.Public.Recheck(CHAT), "OriginalCheckoutDirty")
        fixture.send(WorktreeIntent.Public.Recheck(CHAT))
        val next = RequestId("next")
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, next))
        proceed.complete(Unit)
        choice.await()
        val task = checkNotNull(fixture.journal.taskProjection(CHAT))
        assertEquals(next, task.run?.request)
        assertEquals(WorktreePhase.Working, task.phase)
        assertNull(task.actionRequest)
        assertNull(task.expectedAction)
        assertEquals(0, fixture.git.promptCount)
        assertEquals(1, fixture.builds.acquireCount)
        assertEquals(1, fixture.builds.releaseCount)
    }

    @Test
    fun `existing checkout continues after mode disabled and stale turn cannot release newer merge lease`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        fixture.toggles.isEnabled = false
        fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.Merge))
        val action = checkNotNull(fixture.task().actionRequest)
        fixture.send(WorktreeIntent.Public.ActionDelivered(CHAT, action.operation))
        val mergeRequest = RequestId(action.operation)
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, mergeRequest, WorktreeRunKind.Merge))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, mergeRequest, SESSION, MERGE_TURN))
        val nested = WorktreeIntent.Public.RunBuild(CHAT, "nested", "test")
        val nestedFailure = assertFailsWith<IllegalStateException> { fixture.send(nested) }
        assertEquals("MergeInProgress", nestedFailure.message)
        fixture.send(WorktreeIntent.Public.RunSettled(CHAT, REQUEST, SESSION, TURN, TurnOutcome.Completed))
        assertEquals(0, fixture.builds.releaseCount)
        fixture.send(
            WorktreeIntent.Public.RunObservationLost(CHAT, mergeRequest, SESSION, MERGE_TURN, "ObservationLost"),
        )
        assertEquals(0, fixture.builds.releaseCount)
        assertNull(checkNotNull(fixture.task().run).outcome)
        fixture.send(WorktreeIntent.Public.RunSettled(CHAT, mergeRequest, SESSION, MERGE_TURN, TurnOutcome.Cancelled))
        assertEquals(1, fixture.builds.releaseCount)
    }

    @Test
    fun `lost pending merge submission persists recovery and holds its lease until native settlement`() = runTest {
        for (isNativeIdle in listOf(false, true)) {
            val fixture = Fixture()
            fixture.completeCoding()
            fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.Merge))
            val action = checkNotNull(fixture.task().actionRequest)
            val request = RequestId(action.operation)
            fixture.send(WorktreeIntent.Public.ActionDelivered(CHAT, action.operation))
            fixture.send(WorktreeIntent.Public.RunStarted(CHAT, request, WorktreeRunKind.Merge))
            fixture.send(WorktreeIntent.Public.RunSubmissionObservationLost(CHAT, request, SESSION, "ObservationLost"))
            assertEquals(WorktreePhase.RecoveryRequired, fixture.task().phase)
            assertEquals(SESSION, fixture.task().run?.session)
            assertNull(fixture.task().run?.turn)
            assertNull(fixture.task().run?.outcome)
            assertEquals(0, fixture.builds.releaseCount)
            val restored = checkNotNull(fixture.reopen().load()[CHAT])
            assertEquals(WorktreePhase.RecoveryRequired, restored.phase)
            assertEquals(fixture.task().run, restored.run)
            assertEquals(0, fixture.builds.releaseCount)
            fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("replacement")))
            assertEquals(request, fixture.task().run?.request)
            if (isNativeIdle) {
                fixture.send(WorktreeIntent.Public.RunSubmissionReconciled(CHAT, request, SESSION))
                assertNull(fixture.task().run)
            } else {
                fixture.send(
                    WorktreeIntent.Public.RunSettled(CHAT, request, SESSION, MERGE_TURN, TurnOutcome.Cancelled),
                )
            }
            assertEquals(1, fixture.builds.releaseCount)
        }
    }

    @Test
    fun `only verified PR metadata is exposed and a new coding turn clears it`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.CreatePr))
        val action = checkNotNull(fixture.task().actionRequest)
        val request = RequestId(action.operation)
        fixture.send(WorktreeIntent.Public.ActionDelivered(CHAT, action.operation))
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, request, WorktreeRunKind.CreatePr))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, request, SESSION, MERGE_TURN))
        fixture.send(
            WorktreeIntent.Public.TaskCompleteSignaled(
                CHAT,
                SESSION,
                MERGE_TURN,
                "PR created",
                "https://github.com/test/repo/pull/1",
            ),
        )
        assertNull(fixture.task().verifiedPullRequestUrl)
        fixture.git.isActionVerified = false
        fixture.send(WorktreeIntent.Public.RunSettled(CHAT, request, SESSION, MERGE_TURN, TurnOutcome.Completed))
        assertEquals(WorktreePhase.RecoveryRequired, fixture.task().phase)
        assertNull(fixture.task().verifiedPullRequestUrl)
        fixture.git.isActionVerified = true
        fixture.send(WorktreeIntent.Public.Recheck(CHAT))
        assertEquals(WorktreePhase.Retained, fixture.task().phase)
        assertEquals(1, fixture.git.promptCount)
        assertEquals("https://github.com/test/repo/pull/1", fixture.task().verifiedPullRequestUrl)
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("next")))
        assertNull(fixture.task().verifiedPullRequestUrl)
    }

    @Test
    fun `rechecking an older snapshot cannot erase a concurrently accepted coding request`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.git.existsStarted = started
        fixture.git.existsGate = proceed
        val recheck = async { fixture.send(WorktreeIntent.Public.Recheck(CHAT)) }
        started.await()
        val next = RequestId("next")
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, next))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, next, SESSION, MERGE_TURN))
        proceed.complete(Unit)
        recheck.await()
        assertEquals(next, fixture.task().run?.request)
        assertEquals(MERGE_TURN, fixture.task().run?.turn)
        assertEquals(WorktreePhase.Working, fixture.task().phase)
    }

    @Test
    fun `the chat isolation choice overrides the sandbox toggle and stays with the created task`() = runTest {
        val fixture = Fixture()
        fixture.toggles.isSandbox = true
        fixture.journal.load()
        fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT, isSandbox = false))
        assertFalse(fixture.task().isSandbox)
        fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT, isSandbox = true))
        assertFalse(fixture.task().isSandbox)
        fixture.send(WorktreeIntent.Public.Prepare("sandbox", PROJECT, isSandbox = true))
        assertTrue(fixture.task().isSandbox)
    }

    @Test
    fun `a preparation failing before planning keeps the chosen isolation`() = runTest {
        val fixture = Fixture()
        fixture.toggles.isSandbox = true
        fixture.journal.load()
        fixture.git.hasPlanFailure = true
        listOf(true, false).forEach { isSandbox ->
            val prepare = WorktreeIntent.Public.Prepare("chat-$isSandbox", PROJECT, isSandbox)
            assertFailsWith<IllegalStateException> { fixture.send(prepare) }
            assertEquals(isSandbox, fixture.journal.failed(prepare, "GitProcessUnavailable")?.isSandbox)
        }
    }

    @Test
    fun `a sandbox choice falls back to a worktree while sandboxes are off`() = runTest {
        val fixture = Fixture()
        fixture.journal.load()
        fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT, isSandbox = true))
        assertFalse(fixture.task().isSandbox)
    }

    @Test
    fun `sandbox toggle creates sandbox tasks whose builds carry a fresh snapshot`() = runTest {
        val fixture = Fixture()
        fixture.toggles.isSandbox = true
        fixture.completeCoding()
        assertTrue(fixture.task().isSandbox)
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("build-turn")))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, RequestId("build-turn"), SESSION, MERGE_TURN))
        fixture.send(
            WorktreeIntent.Public.ProposeBuildPlan(
                CHAT,
                WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build"))),
            ),
        )
        fixture.send(WorktreeIntent.Public.RunBuild(CHAT, "build", "test", 1, BUILD_IDENTITY))
        assertEquals("snapshot-build", fixture.builds.executed.single().sandbox?.snapshot)
        assertEquals(WorktreeBuildPhase.Completed, fixture.task().builds["build"]?.phase)
    }

    @Test
    fun `a seal records the captured commit while no native turn works in the checkout`() = runTest {
        val fixture = Fixture()
        fixture.journal.load()
        fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT))
        fixture.send(WorktreeIntent.Public.Seal(CHAT, "seal-1"))
        assertEquals(WorktreeSeal("seal-1", "seal-seal-1"), fixture.task().seal)
        assertEquals(listOf("seal-1"), fixture.git.sealed)
        assertEquals(WorktreeSeal("seal-1", "seal-seal-1"), fixture.reopen().load()[CHAT]?.seal)

        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, REQUEST))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, REQUEST, SESSION, TURN))
        val refused = WorktreeIntent.Public.Seal(CHAT, "seal-2")
        assertFailsWith<IllegalStateException> { fixture.send(refused) }
        val failed = checkNotNull(fixture.journal.failed(refused, "TurnActive"))
        assertEquals(WorktreeSeal("seal-2", failure = "TurnActive"), failed.seal)
        assertEquals(WorktreePhase.Working, failed.phase)
        assertEquals(listOf("seal-1"), fixture.git.sealed)

        fixture.settle(WorktreeRunIdentity(REQUEST, SESSION, TURN))
        fixture.send(WorktreeIntent.Public.Seal(CHAT, "seal-3"))
        assertEquals(WorktreeSeal("seal-3", "seal-seal-3"), fixture.task().seal)
        assertQueuedRejection(fixture.journal, fixture::send) { started, gate ->
            fixture.git.existsStarted = started
            fixture.git.existsGate = gate
        }
    }

    @Test
    fun `a seal of an unknown task or a checkout that vanished fails without a commit`() = runTest {
        val fixture = Fixture()
        fixture.journal.load()
        fixture.send(WorktreeIntent.Public.Seal("unknown", "seal-1"))
        assertEquals(emptyList(), fixture.git.sealed)
        fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT))
        fixture.git.isCheckoutPresent = false
        assertFailsWith<IllegalStateException> { fixture.send(WorktreeIntent.Public.Seal(CHAT, "seal-1")) }
        assertEquals(emptyList(), fixture.git.sealed)
    }

    @Test
    fun `a retained sandbox reclaims ignored outputs once without changing the visible revision`() = runTest {
        val fixture = Fixture()
        fixture.toggles.isSandbox = true
        fixture.completeCoding()
        fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.Leave))
        val retained = fixture.task()
        assertEquals(WorktreePhase.Retained, retained.phase)
        assertEquals(1, fixture.git.reclaimCount)
        assertEquals(retained, fixture.journal.taskProjection(CHAT))
        fixture.send(WorktreeIntent.Public.Recheck(CHAT))
        fixture.reopen().load()
        assertEquals(1, fixture.git.reclaimCount)
    }

    @Test
    fun `a sandbox with a live native run keeps its outputs and ownership through every cleanup check`() = runTest {
        val fixture = Fixture()
        fixture.toggles.isSandbox = true
        fixture.journal.load()
        fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT))
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, REQUEST))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, REQUEST, SESSION, TURN))
        val working = fixture.task()
        fixture.send(WorktreeIntent.Public.Recheck(CHAT))
        backgroundScope.launch { fixture.journal.observeWorkers { } }
        runCurrent()
        backgroundScope.launch { fixture.reopen().observeWorkers { } }
        runCurrent()
        assertEquals(0, fixture.git.reclaimCount)
        assertEquals(working.run, fixture.journal.taskProjection(CHAT)?.run)
        assertEquals(working.executionWorkspace, fixture.journal.taskProjection(CHAT)?.executionWorkspace)
        assertEquals(CHECKOUT, fixture.journal.find(CHECKOUT)?.executionWorkspace)
    }

    @Test
    fun `a retained sandbox waits for cleanup before preparing a new native turn`() = runTest {
        val fixture = Fixture()
        fixture.toggles.isSandbox = true
        fixture.completeCoding()
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.git.reclaimStarted = started
        fixture.git.reclaimGate = proceed
        val leaving = async { fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.Leave)) }
        started.await()
        val request = RequestId("resumed")
        val resumed = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.RunStarted(CHAT, request))
        }

        assertFalse(resumed.isCompleted)
        assertEquals(WorktreePhase.Retained, fixture.journal.taskProjection(CHAT)?.phase)
        proceed.complete(Unit)
        leaving.await()
        resumed.await()
        assertEquals(request, fixture.task().run?.request)
        assertTrue(fixture.task().run?.isPrepared == true)

        // The cleanup marker is cleared atomically with the new turn, so its later outputs are reclaimed too.
        val identity = WorktreeRunIdentity(request, SESSION, MERGE_TURN)
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, request, SESSION, MERGE_TURN))
        fixture.send(WorktreeIntent.Public.TaskCompleteSignaled(CHAT, SESSION, MERGE_TURN, "Done again"))
        fixture.settle(identity)
        fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.Leave))
        assertEquals(2, fixture.git.reclaimCount)
    }

    @Test
    fun `worktree tasks created before sandbox mode keep building in their checkout`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        fixture.toggles.isSandbox = true
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("build-turn")))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, RequestId("build-turn"), SESSION, MERGE_TURN))
        fixture.send(
            WorktreeIntent.Public.ProposeBuildPlan(
                CHAT,
                WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build"))),
            ),
        )
        fixture.send(WorktreeIntent.Public.RunBuild(CHAT, "build", "test", 1, BUILD_IDENTITY))
        assertFalse(fixture.task().isSandbox)
        assertNull(fixture.builds.executed.single().sandbox)
        assertEquals(0, fixture.git.snapshotCount)
    }

    @Test
    fun `explicit worker reconciliation resolves unknown status without replaying the command`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("build-turn")))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, RequestId("build-turn"), SESSION, MERGE_TURN))
        fixture.send(
            WorktreeIntent.Public.ProposeBuildPlan(
                CHAT,
                WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build"))),
            ),
        )
        fixture.builds.phase = WorktreeBuildPhase.Unknown
        fixture.send(WorktreeIntent.Public.RunBuild(CHAT, "build", "test", 1, BUILD_IDENTITY))
        assertEquals(WorktreePhase.RecoveryRequired, fixture.task().phase)
        fixture.builds.phase = WorktreeBuildPhase.Completed
        fixture.send(WorktreeIntent.Public.Recheck(CHAT))
        assertEquals(WorktreeBuildPhase.Completed, fixture.task().builds["build"]?.phase)
        assertEquals(1, fixture.builds.executeCount)
        assertNull(fixture.task().failure)
    }

    @Test
    fun `a sandbox snapshot whose build cannot be queued is discarded`() = runTest {
        val fixture = Fixture()
        fixture.toggles.isSandbox = true
        fixture.completeCoding()
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("build-turn")))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, RequestId("build-turn"), SESSION, MERGE_TURN))
        val plan = WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build")))
        fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, plan))
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.git.buildStarted = started
        fixture.git.buildGate = proceed
        val pending = async {
            assertFailsWith<IllegalStateException> {
                fixture.send(WorktreeIntent.Public.RunBuild(CHAT, "build", "test", 1, BUILD_IDENTITY))
            }
        }
        started.await()
        fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, plan))
        proceed.complete(Unit)
        assertEquals("BuildConfigurationChanged", pending.await().message)
        assertEquals(listOf("build"), fixture.git.discarded)
        assertEquals(0, fixture.builds.executeCount)
    }

    @Test
    fun `restoration prunes snapshot refs of repositories with sandbox tasks keeping active builds`() = runTest {
        val fixture = Fixture()
        fixture.toggles.isSandbox = true
        fixture.completeCoding()
        val restored = fixture.reopen()
        restored.load()
        assertEquals(emptyList(), fixture.git.pruned)
        backgroundScope.launch { restored.observeWorkers {} }
        runCurrent()
        assertEquals(listOf(emptySet<String>()), fixture.git.pruned)
    }

    @Test
    fun `changing configuration during command preparation invalidates the approved revision atomically`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, RequestId("build-turn")))
        fixture.send(WorktreeIntent.Public.RunAccepted(CHAT, RequestId("build-turn"), SESSION, MERGE_TURN))
        val plan = WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build")))
        fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, plan))
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.git.buildStarted = started
        fixture.git.buildGate = proceed
        val pending = async {
            assertFailsWith<IllegalStateException> {
                fixture.send(WorktreeIntent.Public.RunBuild(CHAT, "build", "test", 1, BUILD_IDENTITY))
            }
        }
        started.await()
        fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, plan))
        proceed.complete(Unit)
        assertEquals("BuildConfigurationChanged", pending.await().message)
        assertEquals(0, fixture.builds.executeCount)
        assertNull(fixture.task().builds["build"])
    }

    @Test
    fun `an old hosted turn cannot enqueue or configure after a replacement turn is accepted`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val old = WorktreeRunIdentity(RequestId("old"), SESSION, TURN)
        fixture.accept(old)
        val plan = WorktreeBuildPlan("test", listOf(WorktreeBuildCommand("test", "build")))
        fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, plan, old))
        fixture.settle(old)
        fixture.accept(BUILD_IDENTITY)
        val late = WorktreeIntent.Public.RunBuild(CHAT, "late", "test", 1, old)
        assertEquals("TurnUnavailable", assertFailsWith<IllegalStateException> { fixture.send(late) }.message)
        fixture.journal.failed(late, "TurnUnavailable")
        assertEquals(emptyMap(), fixture.journal.taskProjection(CHAT)?.builds)
        fixture.send(WorktreeIntent.Public.ProposeBuildPlan(CHAT, plan, old))
        assertEquals(1, fixture.task().buildConfigurationRevision)
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.git.buildStarted = started
        fixture.git.buildGate = proceed
        val preparing = async {
            assertFailsWith<IllegalStateException> {
                fixture.send(WorktreeIntent.Public.RunBuild(CHAT, "preparing", "test", 1, BUILD_IDENTITY))
            }
        }
        started.await()
        fixture.settle(BUILD_IDENTITY)
        fixture.accept(WorktreeRunIdentity(RequestId("latest"), SESSION, TurnId("latest")))
        proceed.complete(Unit)
        assertEquals("TurnUnavailable", preparing.await().message)
        assertEquals(0, fixture.builds.executeCount)
        assertEquals(emptyMap(), fixture.task().builds)
    }

    @Test
    fun `trusted early hosted context binds pending native acceptance before Studio acknowledgement`() = runTest {
        val fixture = Fixture()
        fixture.journal.load()
        fixture.send(WorktreeIntent.Public.Prepare(CHAT, PROJECT))
        fixture.send(WorktreeIntent.Public.RunStarted(CHAT, REQUEST))
        val context = AgentToolContext(SESSION, CHECKOUT, TURN, request = REQUEST)
        assertNull(fixture.journal.authenticated(context.copy(request = RequestId("other"))))
        val bound = checkNotNull(fixture.journal.authenticated(context))
        assertEquals(SESSION, bound.run?.session)
        assertEquals(TURN, bound.run?.turn)
    }
}

/** Handoff of a virtual sandbox root and its explicit reopening, on the real journal path. */
class WorktreeHandOffJournalTest {
    @Test
    fun `a plain recheck keeps a handed-off sandbox and only an explicit reopen continues it`() = runTest {
        val fixture = Fixture()
        fixture.handOffVirtualRoot()
        fixture.send(WorktreeIntent.Public.Recheck(CHAT))
        fixture.send(WorktreeIntent.Public.Recheck(CHAT, isResume = true))
        assertEquals(0, fixture.git.reopenCount)
        assertEquals(VirtualSandboxPhase.HandedOff, fixture.task().virtualSandbox?.phase)
        assertEquals("snapshot", fixture.task().virtualSandbox?.resultCommit)
        assertEquals(WorktreePhase.Retained, fixture.task().phase)
        fixture.send(WorktreeIntent.Public.Recheck(CHAT, isReopen = true))
        assertEquals(1, fixture.git.reopenCount)
        assertEquals(VirtualSandboxPhase.Ready, fixture.task().virtualSandbox?.phase)
        assertNull(fixture.task().virtualSandbox?.resultCommit)
    }

    @Test
    fun `a new turn waits for a running handoff and is never retained by it`() = runTest {
        val fixture = Fixture()
        fixture.completeVirtualRoot()
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.git.handoffStarted = started
        fixture.git.handoffGate = proceed
        val handing = async { fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.HandOff)) }
        started.await()
        val request = RequestId("follow-up")
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.RunStarted(CHAT, request))
        }
        assertFalse(next.isCompleted)
        proceed.complete(Unit)
        handing.await()
        next.await()
        assertEquals(1, fixture.git.handoffCount)
        assertEquals(request, fixture.task().run?.request)
        assertEquals(WorktreePhase.Working, fixture.task().phase)
    }

    @Test
    fun `an explicit reopen waits for a running handoff and then reopens the published branch`() = runTest {
        val fixture = Fixture()
        fixture.completeVirtualRoot()
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        fixture.git.handoffStarted = started
        fixture.git.handoffGate = proceed
        val handing = async { fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.HandOff)) }
        started.await()
        val reopening = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.send(WorktreeIntent.Public.Recheck(CHAT, isReopen = true))
        }
        assertFalse(reopening.isCompleted)
        proceed.complete(Unit)
        handing.await()
        reopening.await()
        assertEquals(1, fixture.git.handoffCount)
        assertEquals(1, fixture.git.reopenCount)
        assertEquals(VirtualSandboxPhase.Ready, fixture.journal.taskProjection(CHAT)?.virtualSandbox?.phase)
    }

    @Test
    fun `a handoff choice of a plain worktree changes nothing`() = runTest {
        val fixture = Fixture()
        fixture.completeCoding()
        val before = fixture.task()
        fixture.send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.HandOff))
        assertEquals(0, fixture.git.handoffCount)
        assertEquals(before, fixture.journal.taskProjection(CHAT))
    }
}

private class Fixture {
    val stores = MemoryStores()
    val git = FakeGit()
    val builds = FakeBuilds()
    val toggles = FakeToggles()
    val workspaces = FakeWorkspaces()
    val journal = reopen()
    private var current: WorktreeTask? = null
    fun reopen(): WorktreeJournal = WorktreeJournal(stores, git, builds, workspaces, toggles)
    suspend fun send(command: WorktreeIntent.Public) {
        journal.apply(command) { current = it }
    }
    fun task(): WorktreeTask = checkNotNull(current)
    suspend fun accept(identity: WorktreeRunIdentity) {
        send(WorktreeIntent.Public.RunStarted(CHAT, identity.request))
        send(WorktreeIntent.Public.RunAccepted(CHAT, identity.request, identity.session, identity.turn))
    }
    suspend fun settle(identity: WorktreeRunIdentity) {
        send(
            WorktreeIntent.Public.RunSettled(
                CHAT,
                identity.request,
                identity.session,
                identity.turn,
                TurnOutcome.Completed,
            ),
        )
    }
    suspend fun completeVirtualRoot() {
        git.isVirtualSupported = true
        toggles.isSandbox = true
        journal.load()
        send(WorktreeIntent.Public.Prepare(CHAT, PROJECT, true))
        send(WorktreeIntent.Public.RunStarted(CHAT, REQUEST))
        send(WorktreeIntent.Public.RunAccepted(CHAT, REQUEST, SESSION, TURN))
        send(WorktreeIntent.Public.TaskCompleteSignaled(CHAT, SESSION, TURN, "Done"))
        send(WorktreeIntent.Public.RunSettled(CHAT, REQUEST, SESSION, TURN, TurnOutcome.Completed))
        assertEquals(WorktreePhase.AwaitingDecision, task().phase)
    }
    suspend fun handOffVirtualRoot() {
        completeVirtualRoot()
        send(WorktreeIntent.Public.ChooseAction(CHAT, WorktreeAction.HandOff))
        assertEquals(VirtualSandboxPhase.HandedOff, task().virtualSandbox?.phase)
    }
    suspend fun completeCoding() {
        journal.load()
        send(WorktreeIntent.Public.Prepare(CHAT, PROJECT))
        send(WorktreeIntent.Public.RunStarted(CHAT, REQUEST))
        send(WorktreeIntent.Public.RunAccepted(CHAT, REQUEST, SESSION, TURN))
        send(WorktreeIntent.Public.TaskCompleteSignaled(CHAT, SESSION, TURN, "Done"))
        send(WorktreeIntent.Public.RunSettled(CHAT, REQUEST, SESSION, TURN, TurnOutcome.Completed))
        assertEquals(WorktreePhase.AwaitingDecision, task().phase)
    }
}

private const val CHAT = "chat"
private val PROJECT = WorkspaceRef("project")
private val CHECKOUT = WorkspaceRef("checkout")
private val REQUEST = RequestId("request")
private val TURN = TurnId("turn")
private val MERGE_TURN = TurnId("merge-turn")
private val SESSION = SessionRef(EngineId("test"), SessionSourceId("test"), "native")
private val BUILD_IDENTITY = WorktreeRunIdentity(RequestId("build-turn"), SESSION, MERGE_TURN)

private class FakeWorkspaces : LocalWorkspaces {
    override val isAvailable = true
    override fun observe(): Flow<List<LocalWorkspace>> = flowOf(emptyList())
    override suspend fun register(directory: String): LocalWorkspace = error("Unused")
    override suspend fun registerManaged(directory: String): LocalWorkspace = LocalWorkspace(CHECKOUT, "checkout")
    override suspend fun resolve(ref: WorkspaceRef): String = "/source"
}

private class FakeGit : WorktreeGit {
    override val isAvailable = true
    override var isVirtualSupported = false
    var handoffCount = 0
    private var virtualState = VirtualSandboxState(baseCommit = "sha", snapshot = "snapshot")
    override suspend fun virtualWorkspace(record: WorktreeRecord): WorkspaceRef? =
        CHECKOUT.takeIf { record.task.virtualSandbox != null }
    override suspend fun inspectVirtual(record: WorktreeRecord): VirtualSandboxState? =
        virtualState.takeIf { record.task.virtualSandbox != null }
    var reopenCount = 0
    var handoffStarted: CompletableDeferred<Unit>? = null
    var handoffGate: CompletableDeferred<Unit>? = null
    override suspend fun handoffVirtual(record: WorktreeRecord): VirtualSandboxState {
        handoffStarted?.complete(Unit)
        handoffGate?.await()
        handoffCount++
        virtualState = virtualState.copy(
            phase = VirtualSandboxPhase.HandedOff,
            revision = virtualState.revision + 1,
            resultCommit = "snapshot",
        )
        return virtualState
    }
    override suspend fun reopenVirtual(record: WorktreeRecord): VirtualSandboxState? {
        if (virtualState.phase != VirtualSandboxPhase.HandedOff) return virtualState
        reopenCount++
        virtualState = virtualState.copy(
            phase = VirtualSandboxPhase.Ready,
            revision = virtualState.revision + 1,
            resultCommit = null,
        )
        return virtualState
    }
    var hasPlanFailure = false
    var promptCount = 0
    var preflightCount = 0
    var hasPromptFailure = false
    var promptStarted: CompletableDeferred<Unit>? = null
    var promptGate: CompletableDeferred<Unit>? = null
    var isActionVerified = true
    var existsStarted: CompletableDeferred<Unit>? = null
    var existsGate: CompletableDeferred<Unit>? = null
    var buildStarted: CompletableDeferred<Unit>? = null
    var buildGate: CompletableDeferred<Unit>? = null
    var snapshotCount = 0
    override suspend fun plan(source: String, identity: String): WorktreeProvision {
        check(!hasPlanFailure) { "GitProcessUnavailable" }
        return WorktreeProvision("/source", "/checkout", "/common", "master", "heartbeat/task", "sha")
    }
    override suspend fun trackMain(source: String): WorktreeProvision = plan(source, "main")
    override suspend fun materialize(record: WorktreeRecord) = Unit
    override suspend fun exists(record: WorktreeRecord): Boolean {
        val gate = existsGate
        existsGate = null
        if (gate != null) {
            existsStarted?.complete(Unit)
            gate.await()
        }
        return isCheckoutPresent
    }
    var isCheckoutPresent = true
    override suspend fun validatePlan(record: WorktreeRecord, plan: WorktreeBuildPlan) = Unit
    override suspend fun build(record: WorktreeRecord, id: String, command: String): BuildExecution {
        buildStarted?.complete(Unit)
        buildGate?.await()
        return BuildExecution(
            id,
            WorktreeBuildCommand(command, "build"),
            record.directory,
            listOf(record.commonDirectory),
            "/jobs/$id",
            sandbox = if (record.task.isSandbox) SandboxBuild("", "/slots", record.directory) else null,
        )
    }
    var reclaimCount = 0
    var reclaimStarted: CompletableDeferred<Unit>? = null
    var reclaimGate: CompletableDeferred<Unit>? = null
    val discarded = mutableListOf<String>()
    val pruned = mutableListOf<Set<String>>()
    override suspend fun discardSnapshot(record: WorktreeRecord, id: String) {
        discarded += id
    }
    override suspend fun pruneSnapshots(record: WorktreeRecord, active: Set<String>) {
        pruned += active
    }
    override suspend fun reclaim(record: WorktreeRecord) {
        reclaimCount++
        reclaimStarted?.complete(Unit)
        reclaimGate?.await()
    }
    override suspend fun snapshot(record: WorktreeRecord, id: String): String {
        snapshotCount++
        return "snapshot-$id"
    }
    val sealed = mutableListOf<String>()
    override suspend fun seal(record: WorktreeRecord, id: String): String {
        check(!isVirtualSupported || virtualState.isReady) { "SandboxPaused" }
        sealed += id
        return "seal-$id"
    }
    override suspend fun actionPrompt(record: WorktreeRecord, merge: Boolean): String {
        promptCount++
        promptStarted?.complete(Unit)
        promptGate?.await()
        check(!hasPromptFailure) { "ActionPromptFailed" }
        return "action"
    }
    override suspend fun mergeLease(record: WorktreeRecord, id: String): BuildExecution = build(
        record,
        id,
        "lease",
    ).copy(isHold = true)
    override suspend fun mergePreflight(record: WorktreeRecord): String {
        preflightCount++
        return "sha"
    }
    override suspend fun verifyAction(record: WorktreeRecord, merge: Boolean, pullRequestUrl: String?) =
        isActionVerified
}

private class FakeBuilds : WorktreeBuildCoordinator {
    var releaseCount = 0
    var acquireCount = 0
    var leaseStarted: CompletableDeferred<Unit>? = null
    var leaseGate: CompletableDeferred<Unit>? = null
    var phase = WorktreeBuildPhase.Completed
    var executeCount = 0
    val executed = mutableListOf<BuildExecution>()
    override suspend fun execute(
        execution: BuildExecution,
        isReattachment: Boolean,
        update: suspend (WorktreeBuildOperation) -> Unit,
    ): WorktreeBuildOperation {
        executeCount++
        executed += execution
        return WorktreeBuildOperation(execution.id, execution.command.id, phase)
    }
    override suspend fun cancel(id: String) = true
    override suspend fun recover(execution: BuildExecution): WorktreeBuildOperation = WorktreeBuildOperation(
        execution.id,
        execution.command.id,
        phase,
    )
    override suspend fun acquireLease(execution: BuildExecution) {
        acquireCount++
        leaseStarted?.complete(Unit)
        leaseGate?.await()
    }
    override suspend fun releaseLease(execution: BuildExecution): Boolean {
        releaseCount++
        return true
    }
}

private class FakeToggles : FeatureToggles {
    var isEnabled = true
    var isSandbox = false

    // This fake only hosts the feature's Boolean flags.
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T =
        (if (toggle == WorktreeSandboxEnabled) isSandbox else isEnabled) as T
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = error("Unused")
}

private class MemoryStores : DataStores {
    override val owner: StorageOwner = StorageOwner.App
    val store = MemoryStore(WorktreeJournalSpec)
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = store
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Unused")
    override fun filesDirectory(name: String): String = error("Unused")
    override suspend fun fire(event: DataEvent) = Unit
}

private class MemoryStore(override val spec: KeyValueSpec) : KeyValueStore {
    private val values = MutableStateFlow(emptyMap<String, Any>())
    var writeStarted: CompletableDeferred<Unit>? = null
    var writeGate: CompletableDeferred<Unit>? = null

    // StoreKey defines the value type in this in-memory test fake.
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(key: StoreKey<T>): Flow<T?> = values.map { it[key.name] as T? }

    // StoreKey defines the value type in this in-memory test fake.
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(key: StoreKey<T>): T? = values.value[key.name] as T?
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        val gate = writeGate
        writeGate = null
        if (gate != null) {
            writeStarted?.complete(Unit)
            gate.await()
        }
        values.value +=
            key.name to value
    }
    override suspend fun remove(key: StoreKey<*>) {
        values.value -= key.name
    }
    override suspend fun clear() {
        values.value = emptyMap()
    }
}

/** An accepted preparation and its cleanup must use the same queue while another operation holds the task lock. */
private suspend fun assertQueuedRejection(
    journal: WorktreeJournal,
    send: suspend (WorktreeIntent.Public) -> Unit,
    holdExists: (CompletableDeferred<Unit>, CompletableDeferred<Unit>) -> Unit,
) = coroutineScope {
    val chat = "chat"
    val request = RequestId("queued-request")
    val entered = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    holdExists(entered, gate)
    val sealing = async(start = CoroutineStart.UNDISPATCHED) {
        send(WorktreeIntent.Public.Seal(chat, "before-run"))
    }
    entered.await()
    val starting = async(start = CoroutineStart.UNDISPATCHED) {
        send(WorktreeIntent.Public.RunStarted(chat, request))
    }
    val rejecting = async(start = CoroutineStart.UNDISPATCHED) {
        send(WorktreeIntent.Public.RunRejected(chat, request, "RequestRejected"))
    }
    assertFalse(starting.isCompleted)
    assertFalse(rejecting.isCompleted)
    gate.complete(Unit)
    sealing.await()
    starting.await()
    rejecting.await()
    val closed = checkNotNull(journal.taskProjection(chat))
    assertNull(closed.run)
    assertEquals(request, closed.closedSubmission)
    assertEquals(WorktreePhase.Failed, closed.phase)
}

private suspend fun assertRepeatedPinnedSeal(
    journal: WorktreeJournal,
    send: suspend (WorktreeIntent.Public) -> Unit,
    task: () -> WorktreeTask,
    reopen: () -> WorktreeJournal,
) {
    journal.load()
    send(WorktreeIntent.Public.Prepare("chat", WorkspaceRef("project"), isSandbox = true, isBasePinned = true))
    val seal = WorktreeIntent.Public.Seal("chat", "proof")
    send(seal)
    val first = task()
    send(seal)
    assertEquals(first.seal, task().seal)
    assertTrue(task().revision > first.revision)
    assertEquals(task().seal, reopen().load()["chat"]?.seal)
}
