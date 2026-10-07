package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptScope
import io.aequicor.heartbeat.feature.harness.api.script.ScriptAgent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptEvents
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHooks
import io.aequicor.heartbeat.feature.harness.api.script.ScriptPrompts
import io.aequicor.heartbeat.feature.harness.api.script.ScriptScheduler
import io.aequicor.heartbeat.feature.harness.api.script.ScriptSessions
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflows
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptScheduler
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptSessions

/** Candidate-local services become externally visible only with their enclosing runtime instance. */
internal class HarnessScriptContext(
    request: HarnessActivationRequest,
    access: HarnessInstanceAccess,
    origins: HarnessCallOrigins,
    private val scheduler: HarnessScriptScheduler,
    private val sessions: HarnessScriptSessions,
) : HarnessRuntimeContext {
    val registrations = HarnessScriptRegistrations(request.harness.name, access, origins)
    override val evaluation = HarnessEvaluationContext.Script(object : HarnessScriptScope {
        override val harness = request.harness.id
        override val name = request.harness.name
        override val item = request.item.id
        override val revision = request.harness.revision
        override val scope = access.scope
        override val events: ScriptEvents get() = registrations
        override val hooks: ScriptHooks get() = registrations
        override val agent: ScriptAgent get() = registrations
        override val sessions: ScriptSessions get() = this@HarnessScriptContext.sessions
        override val scheduler: ScriptScheduler get() = this@HarnessScriptContext.scheduler
        override val prompts: ScriptPrompts get() = unavailable()
        override val workflows: ScriptWorkflows get() = unavailable()
    })

    override fun sealForPublication(): Boolean {
        registrations.sealTools()
        return true
    }

    override fun tryCommitPublication(): Boolean = scheduler.tryCommitPublication()

    override fun close() {
        registrations.close()
        scheduler.close()
        sessions.close()
    }
}

private fun unavailable(): Nothing = throw UnsupportedOperationException("Harness service unavailable")
