package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Script hooks run concurrently under budgets below the facade's enclosing deadline. Results retain snapshot
 * order, independent of completion order. One failed callback cannot discard another callback's known Deny.
 * Text hooks only return additional context; callers retain their original prompt/tool result.
 */
internal class HarnessHookDispatch(
    private val runtime: HarnessRuntime,
    private val sessions: HarnessSessionAdmission,
) {
    private val log = Log.tag("HarnessHooks")

    @HighFrequency
    suspend fun beforePrompt(context: SessionHookContext, text: String): String? {
        log.v { "dispatch harness prompt hooks" }
        val callbacks = callbacks(context) { it.beforePrompt() }
        val values = parallel(callbacks) { entry ->
            invoke(entry, context, 1_500.milliseconds, null) { handler -> handler(context, text) }
        }
        return joined(values, PROMPT_CHARS)
    }

    @HighFrequency
    suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict {
        log.v { "dispatch harness tool hooks" }
        val callbacks = callbacks(call.context) { it.beforeTool() }
        val verdicts = parallel(callbacks) { entry ->
            invoke(
                entry,
                call.context,
                900.milliseconds,
                ToolHookVerdict.Ask("A harness script did not respond"),
            ) { handler -> handler(call) }
        }
        return verdicts.filterIsInstance<ToolHookVerdict.Deny>().firstOrNull()
            ?: verdicts.filterIsInstance<ToolHookVerdict.Ask>().firstOrNull()
            ?: ToolHookVerdict.Continue
    }

    @HighFrequency
    suspend fun afterTool(call: HookedToolCall, result: ScriptToolResult): String? {
        log.v { "dispatch harness tool result hooks" }
        val callbacks = callbacks(call.context) { it.afterTool() }
        val values = parallel(callbacks) { entry ->
            invoke(entry, call.context, 900.milliseconds, null) { handler -> handler(call, result) }
        }
        return joined(values, RESULT_CHARS)
    }

    @HighFrequency
    private suspend fun <H : Any> callbacks(
        context: SessionHookContext,
        select: (HarnessScriptRegistrations) -> List<HarnessCallback<H>>,
    ): List<Pair<HarnessInstance, HarnessCallback<H>>> = runtime.published().flatMap { instance ->
        val script = instance.runtimeContext as? HarnessScriptContext
        if (script == null || !sessions.allows(instance.request.harness.id, context.session)) {
            emptyList()
        } else {
            select(script.registrations).filter { it.isActive }.map { instance to it }
        }
    }

    @HighFrequency
    private suspend fun <H : Any, T> invoke(
        entry: Pair<HarnessInstance, HarnessCallback<H>>,
        context: SessionHookContext,
        budget: Duration,
        failed: T?,
        block: suspend (H) -> T?,
    ): T? {
        val (instance, callback) = entry
        val options = HarnessInvocationOptions(
            origin = callback.origin.merge(HarnessCallOrigin(isHookRestricted = true)),
        )
        val result = runtime.invoke(instance, budget, options) {
            if (sessions.allows(instance.request.harness.id, context.session)) {
                callback.acquire()?.let { block(it) }
            } else {
                null
            }
        }
        return when (result) {
            null -> null

            HarnessInvocationResult.TimedOut -> failed

            is HarnessInvocationResult.Cancelled -> failed

            is HarnessInvocationResult.Completed -> when (val attempt = result.value) {
                is HarnessAttempt.Success -> attempt.value
                is HarnessAttempt.Failure -> failed
            }
        }
    }

    private suspend fun <E, T> parallel(entries: List<E>, block: suspend (E) -> T): List<T> = coroutineScope {
        entries.map { async { block(it) } }.awaitAll()
    }

    private fun joined(values: List<String?>, limit: Int): String? = values.filterNotNull()
        .filter { it.isNotBlank() }.joinToString("\n\n").take(limit).takeIf { it.isNotEmpty() }
}

private const val PROMPT_CHARS = 8_000
private const val RESULT_CHARS = 2_000
