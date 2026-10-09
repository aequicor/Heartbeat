package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolCall
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** Exact host-owned handler snapshot. Holding this value never bypasses current runtime/session admission. */
internal class HarnessToolBinding(
    internal val instance: HarnessInstance,
    internal val registration: HarnessToolRegistration,
) {
    val specification: AgentToolSpec get() = registration.specification
    val key: String get() = with(instance.request) {
        listOf(harness.id.value, item.id.value, generation.toString(), registration.callback.id.toString())
            .joinToString("") { "${it.length}:$it" }
    }
    override fun toString(): String = "HarnessToolBinding(***)"
}

/**
 * Internal script execution seam, called only after facade policy/trust authorization. Bindings pin a generation
 * across approval; an old binding is rejected even when a replacement has the same schema. Actual jobs remain
 * associated with their turn after a logical timeout, until completion confirms their resources can be released.
 */
internal class HarnessToolDispatch(
    private val runtime: HarnessRuntime,
    private val sessions: HarnessSessionAdmission,
) {
    private val calls = MutableStateFlow(CallState())
    private val log = Log.tag("HarnessTools")

    @HighFrequency
    suspend fun bindings(session: SessionRef): List<HarnessToolBinding> = bindingsWhere {
        sessions.allows(it, session)
    }

    /** Declarations may precede a native session id; host-resolved permanent scopes determine that initial set. */
    @HighFrequency
    suspend fun bindings(harnesses: Set<HarnessId>): List<HarnessToolBinding> = bindingsWhere { it in harnesses }

    @HighFrequency
    private suspend fun bindingsWhere(allows: (HarnessId) -> Boolean): List<HarnessToolBinding> =
        runtime.published().flatMap { instance ->
        val context = instance.runtimeContext as? HarnessScriptContext
        if (context == null || !allows(instance.request.harness.id)) {
            emptyList()
        } else {
            context.registrations.tools().filter { it.callback.isActive }.map { HarnessToolBinding(instance, it) }
        }
        }

    @HighFrequency
    suspend fun execute(binding: HarnessToolBinding, call: ScriptToolCall): ScriptToolResult {
        log.v { "dispatch harness script tool" }
        val turn = Turn(call.context.session, call.context.turn)
        if (turn in calls.value.closed || call.context.lifetime?.isActive == false ||
            !sessions.allows(binding.instance.request.harness.id, call.context.session)
        ) {
            return unavailable()
        }
        val callback = binding.registration.callback
        val options = HarnessInvocationOptions(callback.origin, call.context.lifetime) { handle ->
            track(turn, handle)
        }
        val result = runtime.invoke(binding.instance, HarnessInvocationBudget.Tool, options) {
            if (call.context.lifetime?.isActive != false && turn !in calls.value.closed && sessions.allows(
                    binding.instance.request.harness.id,
                    call.context.session,
                )
            ) {
                callback.acquire()?.invoke(call)
            } else {
                null
            }
        }
        return when (result) {
            null -> unavailable()

            is HarnessInvocationResult.Cancelled -> unavailable()

            HarnessInvocationResult.TimedOut -> ScriptToolResult("Harness tool timed out", isError = true)

            is HarnessInvocationResult.Completed -> when (val attempt = result.value) {
                is HarnessAttempt.Success -> attempt.value ?: unavailable()
                is HarnessAttempt.Failure -> ScriptToolResult("Harness tool failed", isError = true)
            }
        }
    }

    @HighFrequency
    suspend fun instructions(scope: AgentToolScope): String {
        log.v { "dispatch harness script instructions" }
        val session = scope.session ?: return ""
        // Freeze the caller's mutable set before detached author code observes it.
        val snapshot = scope.copy(declared = scope.declared?.toSet())
        val callbacks = runtime.published().flatMap { instance ->
            val context = instance.runtimeContext as? HarnessScriptContext
            if (context == null || !sessions.allows(instance.request.harness.id, session)) {
                emptyList()
            } else {
                context.registrations.instructions().filter { it.isActive }.map { instance to it }
            }
        }
        val values = coroutineScope {
            callbacks.map { (instance, callback) ->
                async {
                    val result = runtime.invoke(
                        instance,
                        HarnessInvocationBudget.Instructions,
                        HarnessInvocationOptions(callback.origin),
                    ) {
                        if (sessions.allows(instance.request.harness.id, session)) {
                            callback.acquire()?.invoke(snapshot)
                        } else {
                            null
                        }
                    }
                    val attempt = (result as? HarnessInvocationResult.Completed)?.value
                    (attempt as? HarnessAttempt.Success)?.value
                }
            }.awaitAll()
        }
        return values.filterNotNull().filter { it.isNotBlank() }.joinToString("\n\n")
            .take(HarnessLimits.INSTRUCTION_CHARS)
    }

    /** Closes admission before cancelling; a false result reports actual jobs still retaining their lease. */
    suspend fun finishTurn(session: SessionRef, turn: TurnId): Boolean {
        val key = Turn(session, turn)
        calls.update { it.copy(closed = it.closed + key) }
        val owned = calls.value.active.filter { it.turn == key }.map { it.handle }
        owned.forEach { it.cancel() }
        val isStopped = withTimeoutOrNull(500.milliseconds) {
            owned.forEach { it.awaitStopped() }
            true
        } ?: false
        if (!isStopped) log.w(ScriptFailure()) { "Script tool cleanup awaits actual completion" }
        return isStopped
    }

    @HighFrequency
    private fun track(turn: Turn, handle: HarnessInvocationHandle) {
        log.v { "track actual script tool call" }
        val call = ActualCall(turn, handle)
        while (true) {
            val previous = calls.value
            if (turn in previous.closed) {
                handle.cancel()
                return
            }
            if (calls.compareAndSet(previous, previous.copy(active = previous.active + call))) break
        }
        handle.invokeOnStopped { calls.update { it.copy(active = it.active - call) } }
        // A concurrent finishTurn may have snapshotted before this registration; closed is the authority.
        if (turn in calls.value.closed) handle.cancel()
    }

    private fun unavailable() = ScriptToolResult("Harness tool is no longer available", isError = true)

    private data class Turn(val session: SessionRef, val turn: TurnId) {
        override fun toString(): String = "HarnessToolTurn(***)"
    }
    private data class ActualCall(val turn: Turn, val handle: HarnessInvocationHandle) {
        override fun toString(): String = "ActualHarnessCall(***)"
    }
    private data class CallState(val closed: Set<Turn> = emptySet(), val active: Set<ActualCall> = emptySet())
}
