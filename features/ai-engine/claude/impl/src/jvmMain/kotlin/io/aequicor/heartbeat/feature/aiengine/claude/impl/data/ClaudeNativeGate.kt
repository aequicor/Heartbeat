package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Two SDK callbacks share one exact preflight; native ids and the complete JSON input bind its continuation. */
internal class ClaudeNativeGate(
    private val session: String,
    private val enabled: Set<String>,
    private val workspace: ClaudeNativeWorkspace,
    private val tools: ProfileAgentTools,
    private val context: suspend () -> AgentToolContext?,
) : AutoCloseable {
    private val log = Log.tag("ClaudeNativeGate")
    private val isOpen = AtomicBoolean(true)
    private val prepared = ConcurrentHashMap<String, Prepared>()
    private val hooked = ConcurrentHashMap.newKeySet<String>()
    private val decided = ConcurrentHashMap.newKeySet<String>()

    fun initialization(): JsonObject = buildJsonObject {
        put("subtype", "initialize")
        put(
            "hooks",
            buildJsonObject {
                put(
                    "PreToolUse",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("matcher", enabled.sorted().joinToString("|"))
                                put("hookCallbackIds", JsonArray(listOf(JsonPrimitive(CALLBACK))))
                                put("timeout", CLI_HOOK_TIMEOUT_SECONDS)
                            },
                        ),
                    ),
                )
            },
        )
    }

    /** A successful envelope is insufficient if this CLI did not apply the requested hooks. */
    fun initialized(response: JsonObject) {
        if (response["hooks_applied"] != JsonPrimitive(true)) protocolFailure()
    }

    /** Synchronous reader validation; a slow history observer must never postpone a session mismatch. */
    fun validate(frame: JsonObject) {
        val id = frame.text("session_id")
        if (id != null && id != session) protocolFailure()
        val request = frame["request"] as? JsonObject ?: return
        if (request.text("subtype") == "hook_callback") {
            val input = request["input"] as? JsonObject ?: protocolFailure()
            if (input.text("session_id") != session) protocolFailure()
        }
    }

    suspend fun handle(request: JsonObject): JsonObject {
        if (!isOpen.get()) protocolFailure()
        val response = when (request.text("subtype")) {
            "hook_callback" -> guardedHook(request)
            "can_use_tool" -> guardedPermission(request)
            else -> protocolFailure()
        }
        currentCoroutineContext().ensureActive()
        if (!isOpen.get()) protocolFailure()
        return response
    }

    private suspend fun guardedHook(request: JsonObject): JsonObject = try {
        withTimeoutOrNull(HOOK_TIMEOUT_MS) { hook(request) } ?: run {
            log.w { "Native preflight timed out; denying tool call" }
            refuseHook(request, "Native preflight timed out")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.redacted()) { "Native preflight failed; denying tool call" }
        refuseHook(request, "Native preflight failed")
    }

    private fun refuseHook(request: JsonObject, reason: String): JsonObject {
        val id = (request["input"] as? JsonObject)?.text("tool_use_id")
        if (id != null) {
            prepared.remove(id)
            decided.add(id)
        }
        return hookDecision(NativePreparation.Deny(reason))
    }

    private suspend fun guardedPermission(request: JsonObject): JsonObject = try {
        permission(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.redacted()) { "Native permission failed; denying tool call" }
        permissionDecision(NativeVerdict.Deny("Native permission failed"), buildJsonObject {})
    }

    private suspend fun hook(request: JsonObject): JsonObject {
        if (request.text("callback_id") != CALLBACK) protocolFailure()
        val input = request["input"] as? JsonObject ?: protocolFailure()
        if (input.text("session_id") != session || input.text("hook_event_name") != "PreToolUse") protocolFailure()
        val call = call(input, "tool_input")
        val id = identity(input)
        if (request.text("tool_use_id") != id || hooked.size >= MAX_CALLS || !hooked.add(id)) protocolFailure()
        val decision = prepare(call)
        prepared[id] = Prepared(call, decision)
        return hookDecision(decision)
    }

    private suspend fun permission(request: JsonObject): JsonObject {
        val call = call(request, "input")
        val id = identity(request)
        if (decided.size >= MAX_CALLS || !decided.add(id)) protocolFailure()
        val cached = prepared.remove(id)
        val decision = cached?.takeIf { it.call == call }?.decision
        val verdict = when (decision) {
            is NativePreparation.Ask -> decision.confirmation.authorize()
            is NativePreparation.Deny -> NativeVerdict.Deny(decision.reason)
            NativePreparation.Allow, null -> authorize(call)
        }
        return permissionDecision(verdict, call.input)
    }

    private suspend fun prepare(call: Call): NativePreparation {
        val trusted = context()
        val native = workspace.classify(call.name, call.input)
        return if (trusted == null || native == null) {
            NativePreparation.Deny("Native turn ended or tool arguments are invalid")
        } else {
            tools.prepareNative(trusted, native)
        }
    }

    private suspend fun authorize(call: Call): NativeVerdict {
        val trusted = context()
        val native = workspace.classify(call.name, call.input)
        return if (trusted == null || native == null) {
            NativeVerdict.Deny("Native turn ended or tool arguments are invalid")
        } else {
            tools.authorizeNative(trusted, native)
        }
    }

    private fun call(request: JsonObject, inputKey: String): Call {
        val name = request.text("tool_name")?.takeIf { it in enabled } ?: protocolFailure()
        return Call(name, request[inputKey] as? JsonObject ?: protocolFailure())
    }

    override fun close() {
        isOpen.set(false)
        prepared.clear()
    }
}

private fun identity(input: JsonObject): String = input.text("tool_use_id")
    ?.takeIf { it.isNotBlank() && it.length <= MAX_TOOL_ID_CHARS } ?: protocolFailure()

private fun hookDecision(decision: NativePreparation): JsonObject = buildJsonObject {
    put(
        "hookSpecificOutput",
        buildJsonObject {
            put("hookEventName", "PreToolUse")
            put(
                "permissionDecision",
                when (decision) {
                    NativePreparation.Allow -> "allow"
                    is NativePreparation.Deny -> "deny"
                    is NativePreparation.Ask -> "ask"
                },
            )
            if (decision is NativePreparation.Deny) put("permissionDecisionReason", decision.reason)
        },
    )
}

private fun permissionDecision(verdict: NativeVerdict, input: JsonObject): JsonObject = buildJsonObject {
    when (verdict) {
        NativeVerdict.Allow -> {
            put("behavior", "allow")
            put("updatedInput", input)
        }

        is NativeVerdict.Deny -> {
            put("behavior", "deny")
            put("message", verdict.reason)
        }
    }
}

private data class Call(val name: String, val input: JsonObject) {
    override fun toString(): String = "ClaudeNativeCall"
}
private data class Prepared(val call: Call, val decision: NativePreparation) {
    override fun toString(): String = "ClaudeNativePreparation"
}
private const val CALLBACK = "heartbeat-native-pre-tool"
private const val MAX_CALLS = 4_096

private const val MAX_TOOL_ID_CHARS = 256

private const val HOOK_TIMEOUT_MS = 5_000L
private const val CLI_HOOK_TIMEOUT_SECONDS = 60
