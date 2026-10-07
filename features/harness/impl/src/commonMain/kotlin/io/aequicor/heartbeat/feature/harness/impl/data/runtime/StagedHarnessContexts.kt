package io.aequicor.heartbeat.feature.harness.impl.data.runtime

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.api.script.ScriptAgent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptEvents
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHooks
import io.aequicor.heartbeat.feature.harness.api.script.ScriptPrompts
import io.aequicor.heartbeat.feature.harness.api.script.ScriptScheduler
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSessions
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflows
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntimeContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntimeContextFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import kotlinx.coroutines.flow.MutableStateFlow

/** Constructs private candidate contexts. Services are added with their dispatch and quota owners. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StagedHarnessContexts : HarnessRuntimeContextFactory {
    override fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessRuntimeContext =
        when (request.item) {
            is HarnessItem.Script -> PendingScriptContext(request, access)

            is HarnessItem.Workflow -> HarnessWorkflowContext()

            is HarnessItem.Skill, is HarnessItem.Instruction, is HarnessItem.Template ->
                error("Only code items have an execution context")
        }
}

/** Holds exactly one definition; its body is never executed by activation. The runtime owns its code lease. */
internal class HarnessWorkflowContext : HarnessRuntimeContext {
    private val log = Log.tag("HarnessWorkflowContext")
    private val registered = MutableStateFlow<WorkflowRegistrationState>(WorkflowRegistrationState.Empty)
    val definition: WorkflowDefinition? get() = (registered.value as? WorkflowRegistrationState.Registered)?.definition
    override val isReadyForPublication: Boolean get() = definition != null
    override val evaluation = HarnessEvaluationContext.Workflow(
        WorkflowRegistration {
            log.v { "register workflow definition" }
            check(registered.compareAndSet(WorkflowRegistrationState.Empty, WorkflowRegistrationState.Registered(it))) {
                "Workflow must register exactly one definition"
            }
        },
    )
    override fun close() {
        log.v { "release workflow definition" }
        registered.value = WorkflowRegistrationState.Closed
    }
}

private sealed interface WorkflowRegistrationState {
    data object Empty : WorkflowRegistrationState
    data class Registered(val definition: WorkflowDefinition) : WorkflowRegistrationState {
        override fun toString(): String = "WorkflowRegistrationState.Registered(***)"
    }
    data object Closed : WorkflowRegistrationState
}

/**
 * Executable scope with explicit refusal for services whose dispatchers are not connected yet. Accessing an
 * unavailable service fails candidate evaluation instead of producing a successful but ineffective registration.
 */
private class PendingScriptContext(request: HarnessActivationRequest, access: HarnessInstanceAccess) :
    HarnessRuntimeContext {
    override val evaluation = HarnessEvaluationContext.Script(object : HarnessScriptScope {
        override val harness = request.harness.id
        override val name = request.harness.name
        override val item = request.item.id
        override val revision = request.harness.revision
        override val scope = access.scope
        override val events: ScriptEvents get() = unavailable()
        override val hooks: ScriptHooks get() = unavailable()
        override val sessions: ScriptSessions get() = unavailable()
        override val scheduler: ScriptScheduler get() = unavailable()
        override val agent: ScriptAgent get() = unavailable()
        override val prompts: ScriptPrompts get() = unavailable()
        override val workflows: ScriptWorkflows get() = unavailable()
    })
    override fun close() = Unit
}

private fun unavailable(): Nothing = throw UnsupportedOperationException("Harness service unavailable")
