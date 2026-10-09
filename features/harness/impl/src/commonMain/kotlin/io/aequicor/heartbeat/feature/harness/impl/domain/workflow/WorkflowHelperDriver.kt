package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBindings
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * One generation's helper lane. It never retries an immutable prompt after an exception or restart. A distinct
 * recovery attempt follows an authoritative result/cancel barrier, and is persisted before sending. Its owner
 * cancels and joins all branches before handing these resources to another generation or terminal cleanup.
 */
internal class WorkflowHelperDriver(
    private val run: WorkflowRun,
    private val progress: WorkflowRunJournal,
    private val grants: WorkflowHelperJournal,
    private val resources: WorkflowHelperResources,
    private val helpers: HelperAgents,
    private val bindings: HarnessHelperBindings,
    private val workspace: WorkspaceRef?,
    private val handoff: HelperHandoff,
    private val isAdmitted: suspend () -> Boolean,
    private val permissions: suspend (WorkflowStep, List<PermissionRequest>) -> Unit = { _, _ -> },
    private val nextId: () -> String = { Uuid.random().toHexString() },
) : WorkflowAgentSteps {
    private val log = Log.tag("HarnessWorkflow")

    override suspend fun execute(key: StepKey, digest: String, prompt: String, options: AgentOptions): String = host {
        admitted()
        var step = progress.steps.singleOrNull { it.key == key }
        if (step != null && (step.promptSha != digest || step.helper == null)) {
            throw WorkflowStepFailed(WorkflowFailure.Diverged)
        }
        val prepared = prepare(key, digest, step, options)
        val resource = prepared.first
        val helper = checkNotNull(resource.grant.helper)
        bindings.bind(HarnessHelperBinding(helper, ActionId(run.id.value), run.harness, resource.grant.attachRequest))
        if (step == null) {
            step = WorkflowStep(key, digest, helper = helper, request = resource.grant.request)
            progress.record(step)
        }
        try {
            var current = checkNotNull(step)
            current = if (prepared.second) submit(current, prompt, false) else reconcile(current, prompt)
            while (true) {
                admitted()
                current = observe(current)
                val result = helpers.result(helper, checkNotNull(current.request))
                if (result != null) {
                    check(result.request == current.request) { "Helper result identity changed" }
                    if (result.outcome == HelperOutcome.Unknown) {
                        current = recover(current, prompt)
                    } else {
                        return@host finish(current, result)
                    }
                }
                delay(POLL_INTERVAL)
            }
            @Suppress("UNREACHABLE_CODE") // The loop returns only after durable terminal progress.
            error("Unreachable helper state")
        } finally {
            permissions(checkNotNull(step), emptyList())
            val phase = progress.steps.singleOrNull { it.key == key }?.phase
            if (phase == StepPhase.Completed || phase == StepPhase.Failed) {
                // Cancellation can leave this for the next owner; the terminal memo is already durable.
                while (!resources.release(resource)) delay(POLL_INTERVAL)
            }
        }
    }

    private suspend fun observe(step: WorkflowStep): WorkflowStep {
        val observed = helpers.progress(checkNotNull(step.helper), checkNotNull(step.request))
        if (observed == null) {
            permissions(step, emptyList())
            return step
        }
        check(observed.request == step.request && (step.session == null || observed.session == step.session) &&
            (step.turn == null || observed.turn == step.turn)) { "Helper observation identity changed" }
        val current = step.copy(session = observed.session, turn = observed.turn, phase = StepPhase.Running)
        progress.record(current)
        permissions(current, observed.permissions)
        return current
    }

    /** Fresh creation is allowed only in the same call that acquired its slot, never from a restored null row. */
    private suspend fun prepare(
        key: StepKey,
        digest: String,
        step: WorkflowStep?,
        options: AgentOptions,
    ): Pair<WorkflowHelperResource, Boolean> {
        val pending = resources.records(run.id).singleOrNull { it.key == key }
        if (pending != null) {
            if (pending.digest != digest || pending.harness != run.harness || pending.parent != run.caller ||
                (step?.helper != null && pending.helper != null && step.helper != pending.helper)
            ) throw WorkflowStepFailed(WorkflowFailure.Diverged)
            val resource = resources.acquire(pending)
            if (resource.grant.helper != null && !resource.isClosing && !resource.isReleased) {
                persistGrant(resource)
                return resource to false
            }
            while (!resources.release(resource)) delay(POLL_INTERVAL)
        }
        admitted()
        val attachment = step?.helper?.let { helper ->
            val binding = checkNotNull(bindings.lookup(helper)) { "Workflow helper binding is missing" }
            check(binding.owner == ActionId(run.id.value) && binding.harness == run.harness)
            binding.attachRequest
        } ?: RequestId(nextId())
        val grant = WorkflowHelperGrant(
            ActionId("wf_slot_" + nextId()), run.id, run.harness, key, digest, run.caller,
            step?.request ?: RequestId(nextId()), attachment,
        )
        val resource = resources.acquire(grant, step?.helper)
        persistGrant(resource)
        admitted()
        val helper = step?.helper ?: helpers.create(resource.lease, workspace, options.target, options.title)
        resource.bind(helper)
        persistGrant(resource)
        return resource to (step == null)
    }

    private suspend fun persistGrant(resource: WorkflowHelperResource) {
        durable { grants.granted(resource.grant.copy(helper = null)) }
        resource.grant.helper?.let { helper -> durable { grants.bind(resource.grant.reservation, helper) } }
    }

    private suspend fun submit(step: WorkflowStep, text: String, isRecovery: Boolean): WorkflowStep {
        admitted()
        val request = checkNotNull(step.request)
        val result = helpers.prompt(checkNotNull(step.helper), HelperPrompt(request, text, isRecovery, handoff))
        check(result.request == request) { "Helper submission identity changed" }
        return when (result) {
            is HelperSubmission.Accepted -> step.copy(session = result.session, phase = StepPhase.Running).also {
                progress.record(it)
            }
            is HelperSubmission.NotSubmitted -> {
                progress.fail(step.key, WorkflowFailure.Interrupted)
                throw WorkflowStepFailed(WorkflowFailure.Interrupted)
            }
        }
    }

    private suspend fun reconcile(step: WorkflowStep, prompt: String): WorkflowStep {
        val helper = checkNotNull(step.helper)
        val request = checkNotNull(step.request)
        while (true) {
            admitted()
            val result = helpers.result(helper, request)
            if (result != null) {
                check(result.request == request) { "Helper result identity changed" }
                return if (result.outcome == HelperOutcome.Unknown || result.outcome == HelperOutcome.Cancelled) {
                    recover(step, prompt)
                } else {
                    step
                }
            }
            // Null is not permission to send. The exact cancellation must confirm that old work cannot run.
            when (val stopped = helpers.cancel(helper, request)) {
                is HelperCancellation.NotSubmitted -> {
                    check(stopped.request == request)
                    return recover(step, prompt)
                }
                is HelperCancellation.Terminal -> {
                    check(stopped.result.request == request)
                    return if (stopped.result.outcome == HelperOutcome.Unknown ||
                        stopped.result.outcome == HelperOutcome.Cancelled
                    ) recover(step, prompt) else step
                }
                is HelperCancellation.Unconfirmed -> {
                    check(stopped.request == request)
                    delay(POLL_INTERVAL)
                }
            }
        }
    }

    private suspend fun recover(step: WorkflowStep, prompt: String): WorkflowStep {
        admitted()
        val next = step.copy(
            request = RequestId(nextId()), attempt = step.attempt + 1, session = null, turn = null,
            phase = StepPhase.Prepared,
        )
        progress.record(next)
        return submit(next, RECOVERY_PREFIX + prompt, true)
    }

    private suspend fun finish(
        step: WorkflowStep,
        result: HelperResult,
    ): String {
        check(result.request == step.request) { "Helper result identity changed" }
        check(result.turn == null || step.turn == null || result.turn == step.turn) { "Helper terminal turn changed" }
        val value = JsonPrimitive(result.answer)
        val failure = when {
            result.outcome == HelperOutcome.Completed && value.toString().length <= HarnessLimits.RESULT_CHARS -> null
            result.outcome == HelperOutcome.Cancelled -> WorkflowFailure.Interrupted
            else -> WorkflowFailure.Error
        }
        progress.record(
            step.copy(
                turn = step.turn ?: result.turn.takeIf { step.session != null },
                phase = if (failure == null) StepPhase.Completed else StepPhase.Failed,
                result = value.takeIf { failure == null }, failure = failure,
            ),
        )
        if (failure != null) throw WorkflowStepFailed(failure)
        return result.answer
    }

    private suspend fun admitted() {
        currentCoroutineContext().ensureActive()
        if (!isAdmitted()) throw WorkflowExecutionUnavailable()
    }

    /** Retry only an identical journal write, never a helper side effect. */
    private suspend fun <T> durable(block: suspend () -> T): T {
        var reported = false
        while (true) {
            try {
                return block()
            } catch (error: HarnessStorageUncertain) {
                if (!reported) log.w(error) { "Workflow capacity write awaits confirmation" }
                reported = true
                delay(POLL_INTERVAL)
            }
        }
    }

    private suspend fun <T> host(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: WorkflowStepFailed) {
        throw error
    } catch (error: Exception) {
        log.w(harnessScriptFailure(error)) { "Workflow helper operation awaits reconciliation" }
        throw WorkflowExecutionUnavailable()
    }

    private companion object {
        val POLL_INTERVAL = 250.milliseconds
        const val RECOVERY_PREFIX = "The application restarted or lost the previous result. Inspect the existing " +
            "work before continuing; do not repeat completed side effects. Complete the original task:\n\n"
    }
}
