package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A system/init frame establishes session identity, but never proves that the prompt was accepted. */
internal class ClaudeTurnObserver(
    private val ref: SessionRef,
    initial: Turn,
    private val request: PromptRequest,
    private val history: ClaudeHistory,
    private val accepted: CompletableDeferred<TurnId>,
    private val update: (ActiveSessionState) -> Unit,
) {
    @Volatile
    var turn: Turn = initial
        private set
    var hasSession: Boolean = false
        private set
    var hasMatchingSession: Boolean = false
        private set

    @Volatile
    var isFinished: Boolean = false
        private set
    private var isAccepted = false
    private var hasText = false

    @Synchronized
    fun receive(message: JsonObject) {
        val session = message.text("session_id")
        // Any session frame proves the CLI started a native turn, so a mismatch is an ambiguous delivery.
        if (session != null) hasSession = true
        if (session != null && session != ref.nativeId) protocolFailure()
        if (session != null) hasMatchingSession = true
        if (isFinished) return
        when (message.text("type")) {
            "system" -> if (message.text("subtype") == "init") {
                message.text("model")?.let { turn = turn.copy(target = turn.target.copy(model = ModelId(it))) }
            }

            "assistant" -> assistant(message)

            "user" -> toolResults(message)

            "result" -> finish(message)
            // Unknown SDK events are not interpreted as success, authentication errors or permission grants.
        }
    }

    /** A bearer-authenticated tool call proves delivery even when stdout is still buffered. */
    @Synchronized
    fun acceptHostedCall() {
        if (isFinished) return
        hasSession = true
        hasMatchingSession = true
        accept()
    }

    @Synchronized
    fun permissionResolved(id: PermissionRequestId) {
        turn = turn.copy(resolvedPermissions = turn.resolvedPermissions + id)
    }

    private fun assistant(message: JsonObject) {
        if (message["parent_tool_use_id"] != null && message["parent_tool_use_id"] != JsonNull) return
        val body = message["message"] as? JsonObject ?: protocolFailure()
        body.text("model")?.let { turn = turn.copy(target = turn.target.copy(model = ModelId(it))) }
        accept()
        val content = body["content"] as? JsonArray ?: protocolFailure()
        for (entry in content) {
            val part = entry as? JsonObject ?: protocolFailure()
            appendPart(part)
        }
    }

    private fun appendPart(part: JsonObject) {
        when (part.text("type")) {
            "text" -> {
                val value = part.text("text") ?: protocolFailure()
                hasText = true
                history.item(
                    turn.id,
                ) { SessionItem.Message(it, MessageRole.Assistant, listOf(ContentPart.Text(value))) }
            }

            "thinking" -> {
                val value = part.text("thinking") ?: protocolFailure()
                if (value.isNotBlank()) {
                    history.item(turn.id) {
                        SessionItem.Message(it, MessageRole.Assistant, listOf(ContentPart.Reasoning(value)))
                    }
                }
            }

            "tool_use" -> {
                val call = part.text("id") ?: protocolFailure()
                history.item(turn.id) {
                    SessionItem.ToolCall(
                        it,
                        ToolCallId(call),
                        part.text("name").orEmpty(),
                        part["input"]?.toString().orEmpty(),
                        ToolCallStatus.Running,
                    )
                }
            }

            else -> {
                history.item(turn.id) { SessionItem.UnsupportedItem(it, "claude.content") }
            }
        }
    }

    private fun toolResults(message: JsonObject) {
        if (message.text("parent_tool_use_id") != null) return
        val body = message["message"] as? JsonObject ?: return
        val content = body["content"] as? JsonArray ?: return
        for (entry in content) {
            val part = entry as? JsonObject
            val call = part?.takeIf { it.text("type") == "tool_result" }?.text("tool_use_id")
            if (part != null && call != null) {
                val text = part.text("content") ?: (part["content"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.text("text") }.joinToString("\n")
                history.item(turn.id) {
                    SessionItem.ToolResult(
                        it,
                        ToolCallId(call),
                        listOf(ContentPart.Text(text)),
                        if (part.text("is_error") == "true") EngineFailure.Unknown() else null,
                    )
                }
            }
        }
    }

    private fun finish(message: JsonObject) {
        if (message.text("session_id") == null) protocolFailure()
        accept()
        val outcome = if (message.text("subtype") == "success" && message.text("is_error") == "false") {
            TurnOutcome.Completed
        } else {
            TurnOutcome.Failed(EngineFailure.Unknown())
        }
        if (!hasText) {
            message.text("result")?.let { text ->
                history.item(turn.id) { SessionItem.Message(it, MessageRole.Assistant, listOf(ContentPart.Text(text))) }
            }
        }
        isFinished = true
        turn = turn.copy(outcome = outcome)
    }

    /** Publish the result only after stdin was fully written and the process ended. */
    fun complete() {
        val outcome = turn.outcome ?: return
        history.publish { SessionEvent.TurnFinished(it, turn.id, outcome) }
        update(ActiveSessionState.Ready(turn))
    }

    /** A result was observed, but the input write or the process exit did not confirm it. */
    fun resultUnconfirmed() {
        val failure = EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id)
        history.publish { SessionEvent.TurnFinished(it, turn.id, TurnOutcome.Unknown) }
        update(ActiveSessionState.Unavailable(failure, lastTurn = turn.copy(outcome = TurnOutcome.Unknown)))
    }

    private fun accept() {
        if (isAccepted) return
        if (!hasSession) protocolFailure()
        isAccepted = true
        history.publish { SessionEvent.TurnStarted(it, turn) }
        history.item(turn.id) { SessionItem.Message(it, MessageRole.User, request.parts) }
        update(ActiveSessionState.Running(turn))
        accepted.complete(turn.id)
    }
}
