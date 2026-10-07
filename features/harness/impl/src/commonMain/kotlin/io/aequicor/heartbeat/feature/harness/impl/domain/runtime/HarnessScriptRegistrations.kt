package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.event.HarnessEvent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptAgent
import io.aequicor.heartbeat.feature.harness.api.script.ScriptEvents
import io.aequicor.heartbeat.feature.harness.api.script.ScriptHooks
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolCall
import io.aequicor.heartbeat.feature.harness.api.script.ScriptToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.reflect.KClass

internal typealias HarnessBeforePrompt = suspend (SessionHookContext, String) -> String?
internal typealias HarnessBeforeTool = suspend (HookedToolCall) -> ToolHookVerdict
internal typealias HarnessAfterTool = suspend (HookedToolCall, ScriptToolResult) -> String?
internal typealias HarnessInstructions = suspend (AgentToolScope) -> String
internal typealias HarnessToolHandler = suspend (ScriptToolCall) -> ScriptToolResult

/** Typed event matcher paired with an erased callback; matching must precede callback acquisition. */
internal data class HarnessEventRegistration(
    val type: KClass<out HarnessEvent>,
    val callback: HarnessCallback<suspend (HarnessEvent) -> Unit>,
) {
    override fun toString(): String = "HarnessEventRegistration(***)"
}

/** Immutable declaration and separately revocable execution registration. */
internal data class HarnessToolRegistration(
    val specification: AgentToolSpec,
    val callback: HarnessCallback<HarnessToolHandler>,
) {
    override fun toString(): String = "HarnessToolRegistration(***)"
}

/** Candidate-local registrations. Only the runtime publishes their enclosing context. */
internal class HarnessScriptRegistrations(
    private val name: HarnessName,
    private val access: HarnessInstanceAccess,
    private val origins: HarnessCallOrigins,
) : ScriptEvents,
    ScriptHooks,
    ScriptAgent {
    private val state = MutableStateFlow(HarnessRegistrationState())
    private val log = Log.tag("HarnessScriptRegistrations")

    fun events(): List<HarnessEventRegistration> = state.value.events.toList()
    fun beforePrompt(): List<HarnessCallback<HarnessBeforePrompt>> = state.value.beforePrompt.toList()
    fun beforeTool(): List<HarnessCallback<HarnessBeforeTool>> = state.value.beforeTool.toList()
    fun afterTool(): List<HarnessCallback<HarnessAfterTool>> = state.value.afterTool.toList()
    fun instructions(): List<HarnessCallback<HarnessInstructions>> = state.value.instructions.toList()
    fun tools(): List<HarnessToolRegistration> = state.value.tools.toList()

    @HighFrequency
    override fun <E : HarnessEvent> on(type: KClass<E>, handler: suspend (E) -> Unit): ScriptRegistration {
        log.v { "register script event" }
        return register { previous, id ->
            // KClass.isInstance guards this erased adapter before it is delivered by the dispatcher.
            @Suppress("UNCHECKED_CAST")
            val erased: suspend (HarnessEvent) -> Unit = { handler(it as E) }
            val callback = HarnessCallback(id, origins.current(), erased)
            previous.copy(events = previous.events + HarnessEventRegistration(type, callback)) to callback
        }
    }

    @HighFrequency
    override fun beforePrompt(handler: HarnessBeforePrompt): ScriptRegistration {
        log.v { "register prompt hook" }
        return register { previous, id ->
            val callback = HarnessCallback(id, origins.current(), handler)
            previous.copy(beforePrompt = previous.beforePrompt + callback) to callback
        }
    }

    @HighFrequency
    override fun beforeTool(handler: HarnessBeforeTool): ScriptRegistration {
        log.v { "register tool hook" }
        return register { previous, id ->
            val callback = HarnessCallback(id, origins.current(), handler)
            previous.copy(beforeTool = previous.beforeTool + callback) to callback
        }
    }

    @HighFrequency
    override fun afterTool(handler: HarnessAfterTool): ScriptRegistration {
        log.v { "register tool result hook" }
        return register { previous, id ->
            val callback = HarnessCallback(id, origins.current(), handler)
            previous.copy(afterTool = previous.afterTool + callback) to callback
        }
    }

    @HighFrequency
    override fun instructions(handler: HarnessInstructions): ScriptRegistration {
        log.v { "register script instructions" }
        return register { previous, id ->
            val callback = HarnessCallback(id, origins.current(), handler)
            previous.copy(instructions = previous.instructions + callback) to callback
        }
    }

    @HighFrequency
    override fun tool(
        name: ItemName,
        description: String,
        schema: JsonObject,
        action: AgentToolAction,
        handler: HarnessToolHandler,
    ): ScriptRegistration {
        log.v { "register script tool" }
        val spec = AgentToolSpec(
            "hs_${this.name.value}_${name.value}",
            description,
            Json.parseToJsonElement(schema.toString()).jsonObject,
            action,
        )
        return register { previous, id ->
            check(!previous.areToolsSealed) { "Script tool declarations are already published" }
            val callback = HarnessCallback(id, origins.current(), handler)
            previous.copy(tools = previous.tools + HarnessToolRegistration(spec, callback)) to callback
        }
    }

    /** One atomic snapshot freezes candidate tools; later disposal cannot mutate the declaration snapshot. */
    @HighFrequency
    fun sealTools(): List<AgentToolSpec> {
        log.v { "seal script tool declarations" }
        while (true) {
            val previous = state.value
            check(!previous.isClosed) { "Script registrations are closed" }
            if (previous.areToolsSealed) return previous.declarations
            val declarations = previous.tools.filter { it.callback.isActive }.map { it.specification }
            val next = previous.copy(areToolsSealed = true, declarations = declarations)
            if (state.compareAndSet(previous, next)) return declarations
        }
    }

    @HighFrequency
    fun close() {
        log.v { "close script registrations" }
        while (true) {
            val previous = state.value
            if (previous.isClosed) return
            if (state.compareAndSet(previous, HarnessRegistrationState(isClosed = true))) {
                previous.callbacks().forEach { it.dispose() }
                return
            }
        }
    }

    @HighFrequency
    private fun register(
        transform: (HarnessRegistrationState, Long) -> Pair<HarnessRegistrationState, ScriptRegistration>,
    ): ScriptRegistration {
        log.v { "update script registration snapshot" }
        while (true) {
            val previous = state.value
            check(!previous.isClosed && access.isRegistrationAllowed) { "Script activation is unavailable" }
            val (next, registration) = transform(previous.pruned(), previous.nextId)
            if (state.compareAndSet(previous, next.copy(nextId = previous.nextId + 1))) return registration
        }
    }
}

private data class HarnessRegistrationState(
    val nextId: Long = 1,
    val areToolsSealed: Boolean = false,
    val isClosed: Boolean = false,
    val declarations: List<AgentToolSpec> = emptyList(),
    val events: List<HarnessEventRegistration> = emptyList(),
    val beforePrompt: List<HarnessCallback<HarnessBeforePrompt>> = emptyList(),
    val beforeTool: List<HarnessCallback<HarnessBeforeTool>> = emptyList(),
    val afterTool: List<HarnessCallback<HarnessAfterTool>> = emptyList(),
    val instructions: List<HarnessCallback<HarnessInstructions>> = emptyList(),
    val tools: List<HarnessToolRegistration> = emptyList(),
) {
    override fun toString(): String = "HarnessRegistrationState(***)"

    /** Compact revoked entries before growth; acquired callbacks remain owned by their actual invocation. */
    fun pruned(): HarnessRegistrationState = copy(
        events = events.filter { it.callback.isActive },
        beforePrompt = beforePrompt.filter { it.isActive },
        beforeTool = beforeTool.filter { it.isActive },
        afterTool = afterTool.filter { it.isActive },
        instructions = instructions.filter { it.isActive },
        tools = tools.filter { it.callback.isActive },
    )

    fun callbacks(): List<HarnessCallback<*>> =
        events.map { it.callback } + beforePrompt + beforeTool + afterTool + instructions + tools.map { it.callback }
}
