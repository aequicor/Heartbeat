package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionLifecycle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Optional contributions; resolving them lazily avoids a cycle through the facade and feature services. */
@ContributesTo(ProfileScope::class)
public interface SessionHookBindings {
    /** No hooks is a valid profile configuration. */
    @Multibinds(allowEmpty = true)
    public fun sessionHooks(): Set<SessionHook>
}

/** Parallel bounded interception and independent, lossy observation queues per contribution. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class ProfileSessionHooks(
    private val contributions: Lazy<Set<SessionHook>>,
    private val dispatchers: DispatcherProvider,
    @ForScope(ProfileScope::class) private val scope: CoroutineScope,
) : SessionHooks {
    private val log = Log.tag("SessionHooks")
    private val sessions = HookSessions()
    private val entries by lazy { contributions.value.map { Entry(it) } }

    @HighFrequency
    override fun observe(event: SessionLifecycle) {
        if (!sessions.accept(event)) return
        entries.forEach { entry -> if (entry.hook.isObserving) entry.events.trySend(event) }
    }

    override fun bindTurn(context: SessionHookContext) = sessions.bind(context)
    override fun releaseTurn(session: SessionRef, turn: TurnId, isRejected: Boolean) =
        sessions.release(session, turn, isRejected)
    override fun context(session: SessionRef, request: RequestId?, turn: TurnId?): SessionHookContext? =
        sessions.context(session, request, turn)

    @HighFrequency
    override suspend fun beforePrompt(context: SessionHookContext, text: String): String? {
        if (!sessions.isOpen(context)) return null
        return parallel<String?>(PROMPT_MILLIS, null) { it.beforePrompt(context, text) }.joined(PROMPT_CHARS)
    }

    @HighFrequency
    override suspend fun beforeTool(call: HookedToolCall): ToolHookVerdict {
        if (!isBound(call)) return ToolHookVerdict.Continue
        val results = parallel<ToolHookVerdict>(TOOL_MILLIS, ToolHookVerdict.Ask("A session hook did not respond")) {
            it.beforeTool(call)
        }
        return results.filterIsInstance<ToolHookVerdict.Deny>().firstOrNull()
            ?: results.filterIsInstance<ToolHookVerdict.Ask>().firstOrNull()
            ?: ToolHookVerdict.Continue
    }

    @HighFrequency
    override suspend fun afterTool(call: HookedToolCall, result: AgentToolResult): String? {
        if (!isBound(call)) return null
        return parallel<String?>(TOOL_MILLIS, null) { it.afterTool(call, result) }.joined(NOTE_CHARS)
    }

    private fun isBound(call: HookedToolCall): Boolean {
        val context = call.context
        val bound = sessions.context(context.session, context.request, context.turn) ?: return false
        val expected = if (call.isNative && context.turn == null) bound.copy(turn = null) else bound
        return expected == context
    }

    @HighFrequency
    private suspend fun <T> parallel(timeout: Long, fallback: T, block: suspend (SessionHook) -> T): List<T> =
        coroutineScope {
            entries.filter { it.hook.isIntercepting }.map { entry ->
                async { entry.bounded(timeout, fallback) { block(entry.hook) } }
            }.awaitAll()
        }

    private inner class Entry(val hook: SessionHook) {
        private val lane = dispatchers.io.limitedParallelism(1)
        val events = Channel<SessionLifecycle>(EVENT_CAPACITY, BufferOverflow.DROP_OLDEST)

        init {
            scope.launch(lane) {
                for (event in events) bounded(OBSERVE_MILLIS, Unit) { if (hook.isObserving) hook.observe(event) }
            }.invokeOnCompletion { events.close() }
        }

        @HighFrequency
        suspend fun <T> bounded(timeout: Long, fallback: T, block: suspend () -> T): T {
            // Work belongs to the profile, not the waiting caller: non-cooperative code cannot extend its deadline.
            val work = scope.async(lane) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(HookFailure(e::class.simpleName.orEmpty())) { "Session hook failed" }
                    fallback
                }
            }
            return try {
                val result = withTimeoutOrNull(timeout) { ResultValue(work.await()) }
                if (result != null) {
                    result.value
                } else {
                    log.w(HookFailure("Timeout")) { "Session hook timed out" }
                    fallback
                }
            } finally {
                work.cancel()
            }
        }
    }
}

private data class ResultValue<T>(val value: T) {
    override fun toString(): String = "ResultValue"
}

/** Does not retain the original throwable, message, cause or script source. */
private class HookFailure(type: String) : Exception(type)

private fun List<String?>.joined(maxChars: Int): String? =
    filterNotNull().filter(String::isNotBlank).joinToString("\n\n").take(maxChars).takeIf(String::isNotBlank)

private const val PROMPT_MILLIS = 2_000L
private const val TOOL_MILLIS = 1_200L
private const val OBSERVE_MILLIS = 30_000L
private const val EVENT_CAPACITY = 64
private const val NOTE_CHARS = 2_000
private const val PROMPT_CHARS = 8_000
