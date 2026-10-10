package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.harness.api.event.SessionEvent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventDispatch
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessHookDispatch
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessSessionProofs
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** Lazy dispatch paths share one trusted session proof cache without constructing an engine facade. */
internal data class HarnessSessionHookDispatchers(
    val hooks: Lazy<HarnessHookDispatch>,
    val events: Lazy<HarnessEventDispatch>,
    val prepare: suspend (SessionHookContext) -> Unit = {},
) {
    override fun toString(): String = "HarnessSessionHookDispatchers(***)"
}

/** Facade already authenticates owner/turn opt-in; this bridge additionally verifies current harness activation. */
internal class HarnessSessionHook(
    private val gate: HarnessEventGate,
    private val proofs: Lazy<HarnessSessionProofs>,
    private val dispatch: HarnessSessionHookDispatchers,
    private val origins: Lazy<HarnessRequestOrigins>,
    private val clock: Clock,
) : SessionHook {
    private val log = Log.tag("HarnessSessionHook")
    override val isObserving: Boolean get() = gate.isEnabled
    override val isIntercepting: Boolean get() = gate.isEnabled

    @HighFrequency
    override suspend fun observe(event: SessionLifecycle) {
        if (!gate.isEnabled) return
        log.v { "forward trusted session observation" }
        prepare(event.context)
        val at = clock.now()
        val projected = when (event) {
            is SessionLifecycle.Opened -> SessionEvent.Opened(event.context, at)

            is SessionLifecycle.Closed -> SessionEvent.Closed(event.context, at)

            is SessionLifecycle.TurnStarted -> SessionEvent.TurnStarted(event.context, at)

            is SessionLifecycle.TurnFinished -> SessionEvent.TurnFinished(event.context, event.outcome, at)

            is SessionLifecycle.PermissionRequested -> SessionEvent.PermissionRequested(
                event.context,
                event.permission,
                at,
            )
        }
        if (gate.isEnabled) dispatch.events.value.emit(projected, origins.value.origin(event.context))
    }

    @HighFrequency
    override suspend fun beforePrompt(context: SessionHookContext, text: String): String? {
        if (!gate.isEnabled) return null
        log.v { "forward trusted prompt hook" }
        prepare(context)
        return withContext(HarnessOriginContext(origins.value.origin(context))) {
            if (gate.isEnabled) dispatch.hooks.value.beforePrompt(context, text) else null
        }
    }

    @HighFrequency
    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict {
        if (!gate.isEnabled) return ToolHookVerdict.Continue
        log.v { "forward trusted tool hook" }
        prepare(call.context)
        val isInitiallyResolved = proofs.value.isResolved(call.context.session)
        val verdict = withContext(HarnessOriginContext(origins.value.origin(call.context))) {
            if (gate.isEnabled) dispatch.hooks.value.beforeTool(call) else ToolHookVerdict.Continue
        }
        if (verdict != ToolHookVerdict.Continue || !gate.isEnabled) return verdict
        return if (isInitiallyResolved && proofs.value.isResolved(call.context.session)) {
            verdict
        } else {
            ToolHookVerdict.Ask("Harness session activation is not available")
        }
    }

    @HighFrequency
    override suspend fun afterTool(call: HookedToolCall, result: AgentToolResult): String? {
        if (!gate.isEnabled) return null
        log.v { "forward trusted tool result hook" }
        prepare(call.context)
        return withContext(HarnessOriginContext(origins.value.origin(call.context))) {
            if (gate.isEnabled) {
                dispatch.hooks.value.afterTool(call, ScriptToolResult(result.text, result.isError))
            } else {
                null
            }
        }
    }

    @HighFrequency
    private suspend fun prepare(context: SessionHookContext) {
        log.v { "prepare trusted session activation" }
        proofs.value.remember(context)
        if (!proofs.value.isResolved(context.session)) {
            withTimeoutOrNull(200.milliseconds) { dispatch.prepare(context) }
        }
    }
}
