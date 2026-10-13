package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRejection
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRouting
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessOwnedContext
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HARNESS_WAKE_OWNER
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeOperations
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakePort
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeSubmission
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowCodeExecutor
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowLibraryEpoch
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.isWorkflowInput
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.pinWorkflow
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Host-captured candidate shared by approval and execution; callers may never construct it from model JSON. */
internal data class WorkflowLaunch(
    val harness: Harness,
    val workflow: HarnessItem.Workflow,
    val input: JsonObject,
    val caller: SessionRef?,
    val workspace: WorkspaceRef?,
    val initiator: RequestInitiator?,
    val origin: HarnessCallOrigin,
    val kind: WorkflowOrigin,
    val generation: Long,
) {
    override fun toString(): String = "WorkflowLaunch(***)"
}

internal data class WorkflowLaunchResult(val run: RunId, val hasWake: Boolean, val isConfirmed: Boolean)

/** Exact revision, workflow and optional full reviewed snapshot; mismatches refuse launch. */
internal data class ApprovedWorkflow(val revision: Long, val item: ItemId, val snapshot: Harness? = null) {
    fun matches(harness: Harness, workflow: HarnessItem.Workflow): Boolean =
        revision == harness.revision && item == workflow.id && (snapshot == null || snapshot == harness)
}

/** Admission and wake preparation belong to a profile job, independently of the calling script/tool wait. */
@SingleIn(ProfileScope::class)
@Inject
internal class HarnessWorkflowLaunches(
    private val library: Lazy<HarnessMachine>,
    private val runs: Lazy<HarnessRunsMachine>,
    private val storage: HarnessRunStorage,
    private val helpers: Lazy<HelperAgents>,
    private val code: Lazy<WorkflowCodeExecutor>,
    private val active: Lazy<HarnessActiveAccess>,
    private val wakes: Lazy<HarnessWakeOperations>,
    private val wakePort: Lazy<HarnessWakePort>,
    private val toggles: FeatureToggles,
    private val epoch: WorkflowLibraryEpoch,
    private val clock: Clock,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) {
    private val log = Log.tag("HarnessWorkflow")
    private val ownership = HarnessOwnedContext()

    suspend fun prepare(
        harness: HarnessId,
        workflow: ItemName,
        input: JsonObject,
        caller: SessionRef?,
        workspace: WorkspaceRef?,
        initiator: RequestInitiator?,
        origin: HarnessCallOrigin,
        kind: WorkflowOrigin,
        approved: ApprovedWorkflow? = null,
    ): WorkflowLaunch {
        check(!origin.isHookRestricted && toggles.get(HarnessEnabled)) { "Workflow launch is unavailable" }
        check(code.value.isAvailable && helpers.value.canHost(caller)) { "Workflow helpers are unavailable" }
        require(kind != WorkflowOrigin.Agent || (caller != null && initiator?.session == caller))
        val state = library.value.state.value as? HarnessState.Ready ?: error("Harness library is unavailable")
        check(!state.isSuspended && state.isRuntimeAvailable && state.pending[harness] !is HarnessMutation.Remove)
        val current = state.harnesses.singleOrNull { it.harness.id == harness }?.harness
            ?.takeIf { it.isEnabled } ?: error("Harness is unavailable")
        val item = current.items.singleOrNull { it.name == workflow && it.isEnabled } as? HarnessItem.Workflow
            ?: error("Workflow is unavailable")
        check(approved == null || approved.matches(current, item)) {
            "Workflow admission changed"
        }
        require(isWorkflowInput(item.input, input)) { "Workflow input does not match its schema" }
        val launch =
            WorkflowLaunch(current, item, input, caller, workspace, initiator, origin, kind, state.activationGeneration)
        check(isCurrent(launch)) { "Workflow admission changed" }
        return launch
    }

    /**
     * Whether the live library still holds exactly the approved revision and enabled workflow item. A stale
     * approval is an expected race the caller reports as its own outcome; [prepare] still refuses it.
     */
    fun isApproved(harness: HarnessId, workflow: ItemName, approved: ApprovedWorkflow): Boolean {
        val state = library.value.state.value as? HarnessState.Ready ?: return true
        val current = state.harnesses.singleOrNull { it.harness.id == harness }?.harness ?: return true
        val item = current.items.singleOrNull { it.name == workflow }
        return item is HarnessItem.Workflow && approved.matches(current, item)
    }

    suspend fun start(launch: WorkflowLaunch, isCallerCurrent: () -> Boolean = { true }): WorkflowLaunchResult {
        check(isCallerCurrent() && isCurrent(launch)) { "Workflow admission changed" }
        val result = CompletableDeferred<WorkflowLaunchResult>()
        val job = profile.coroutineScope.launch {
            try {
                result.complete(submit(launch, isCallerCurrent))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Host submission runs no author code: its own failure is logged as is.
                log.w(error) { "Workflow launch did not complete" }
                result.completeExceptionally(IllegalStateException("Workflow launch failed"))
            }
        }
        job.invokeOnCompletion { if (it != null) result.completeExceptionally(it) }
        return result.await()
    }

    private suspend fun submit(launch: WorkflowLaunch, isCallerCurrent: () -> Boolean): WorkflowLaunchResult {
        val machine = runs.value
        if (machine.state.value is HarnessRunsState.Idle || machine.state.value is HarnessRunsState.Failed) {
            machine.send(HarnessRunsIntent.Internal.Start)
        }
        val ready = withTimeoutOrNull(5.seconds) { machine.state.first { it is HarnessRunsState.Ready } }
            as? HarnessRunsState.Ready ?: error("Workflow journal is unavailable")
        check(!ready.isSuspended && isCallerCurrent() && isCurrent(launch)) { "Workflow admission changed" }
        val id = RunId("wf_" + Uuid.random().toHexString())
        val at = clock.now()
        val context = ownership.encode(launch.harness.id, launch.origin)
        val wake = launch.caller?.takeIf { toggles.get(SchedulerEnabled) }?.let { caller ->
            WakeRequest(
                WakeId("hw_" + Uuid.random().toHexString()), caller, launch.workspace,
                WakeCondition(setOf(EventKeys.actionFinished(id.action)), at + HarnessLimits.RUN_TIME + 5.minutes),
                "Workflow ${id.value} finished. Read its status and continue with the result.",
                WakeOrigin.Feature(HARNESS_WAKE_OWNER, launch.harness.title), isDeduplicationRequired = true,
                ownerFeature = HARNESS_WAKE_OWNER, ownerContext = context, initiator = launch.initiator,
            )
        }
        val run = WorkflowRun(
            id, launch.harness.id, launch.workflow.id,
            pinWorkflow(launch.harness, launch.workflow) { it.encodeUtf8().sha256().hex() },
            launch.input, launch.caller, launch.kind, at, at + HarnessLimits.RUN_TIME, wake = wake?.id,
            routing = WorkflowRouting(
                launch.workspace,
                HelperHandoff(launch.initiator, HARNESS_WAKE_OWNER, context),
                epoch.value,
                launch.generation,
            ),
        )
        // The journal record precedes every side effect: after a crash past this point Restore drives the run,
        // so neither the run nor a caller wake about a run that never existed can be lost or orphaned.
        record(run)
        var isStartSent = false
        val isAccepted = try {
            if (wake != null) {
                wakes.value.schedule(HarnessWakeSubmission(launch.harness.id, wake, launch.origin, false)) {
                    isCallerCurrent() && isCurrent(launch)
                }
            }
            check(isCallerCurrent() && isCurrent(launch)) { "Workflow admission changed" }
            isStartSent = true
            admit(run)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A failed Start send is uncertain: the durable record then stays and is driven after the next restore.
            if (!isStartSent) withdraw(run, wake)
            throw error
        }
        if (isAccepted == false) {
            withdraw(run, wake)
            error("Workflow start was rejected")
        }
        log.i { "Workflow start submitted origin=${launch.kind} confirmed=${isAccepted == true}" }
        return WorkflowLaunchResult(id, wake != null, isAccepted == true)
    }

    /** A failed or uncertain create is withdrawn: discard removes only this exact unclaimed record, if any. */
    private suspend fun record(run: WorkflowRun) {
        val isSaved = try {
            storage.save(run, null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            withdraw(run, null)
            throw error
        }
        check(isSaved) { "Workflow journal rejected the run" }
    }

    /** Undoes a start that will never run: its scheduled caller wake and its never-claimed journal record. */
    private suspend fun withdraw(run: WorkflowRun, wake: WakeRequest?) {
        try {
            if (wake != null) wakePort.value.cancel(wake)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Workflow caller wake was not withdrawn" }
        }
        try {
            if (!storage.discard(run)) log.w { "Workflow journal record was already claimed; it stays" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Workflow journal record was not withdrawn" }
        }
    }

    /**
     * Null preserves an uncertain Start; its existing run/wake identities must never be resubmitted as new work.
     * A Duplicate rejection means a concurrent restore already adopted the stored record, so it counts as started.
     */
    private suspend fun admit(run: WorkflowRun): Boolean? = coroutineScope {
        val request = RequestId(Uuid.random().toHexString())
        val machine = runs.value
        val receipt = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.first {
                (it is HarnessRunsOutput.Started && it.requestId == request) ||
                    (it is HarnessRunsOutput.Rejected && it.requestId == request)
            }
        }
        try {
            val sent = machine.send(HarnessRunsIntent.Internal.StartRun(request, run, clock.now()))
            when {
                sent != SendResult.Accepted -> false

                (machine.state.value as? HarnessRunsState.Ready)?.runs?.any { it.id == run.id } == true -> true

                else -> withTimeoutOrNull(5.seconds) { receipt.await() }?.let {
                    it is HarnessRunsOutput.Started ||
                        (it as? HarnessRunsOutput.Rejected)?.reason == WorkflowRejection.Duplicate
                }
            }
        } finally {
            receipt.cancel()
        }
    }

    private suspend fun isCurrent(launch: WorkflowLaunch): Boolean {
        if (!toggles.get(HarnessEnabled)) return false
        val state = library.value.state.value as? HarnessState.Ready ?: return false
        if (state.isSuspended || state.pending[launch.harness.id] is HarnessMutation.Remove ||
            state.harnesses.none { it.harness == launch.harness }
        ) {
            return false
        }
        return launch.caller == null || active.value.active(
            launch.workspace,
            launch.caller,
        ).any { it.id == launch.harness.id }
    }
}
