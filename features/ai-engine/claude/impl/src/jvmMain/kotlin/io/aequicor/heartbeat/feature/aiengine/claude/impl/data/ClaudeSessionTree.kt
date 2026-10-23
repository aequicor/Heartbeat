package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionActivity
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeNode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Agent tool identity is stable even before a background task gets its task_id alias. */
@Serializable
internal data class ClaudeChild(
    val call: String,
    val parent: String? = null,
    val name: String,
    val task: String? = null,
    val activity: String = "Queued",
    val isBackground: Boolean? = null,
    val history: ClaudeHistorySnapshot = ClaudeHistorySnapshot(),
    val aliases: Set<String> = emptySet(),
    val frames: Set<String> = emptySet(),
)

/** Persisted observations only; no textual agent-id trailers or response heuristics. */
internal class ClaudeSessionTree(
    private val root: SessionRef,
    restored: List<ClaudeChild> = emptyList(),
    originalInputs: Map<String, List<ContentPart>> = emptyMap(),
) {
    private val inputs = originalInputs.toMutableMap()

    @Synchronized
    fun rememberInputs(references: Map<String, List<ContentPart>>) {
        inputs.putAll(references)
    }

    @Synchronized
    fun inputReferences(): Map<String, List<ContentPart>> = inputs.toMap()

    private val children = restored.associateBy { it.call }.mapValues { (_, child) ->
        if (child.activity in ACTIVE) child.copy(activity = "Unknown") else child
    }.toMutableMap()
    private val histories = restored.associate { it.call to ClaudeHistory(it.history) }.toMutableMap()

    @Synchronized
    fun saved(): List<ClaudeChild> = children.values.map {
        it.copy(
            history = checkNotNull(histories[it.call]).snapshot(),
        )
    }

    @Synchronized
    fun snapshot(rootActivity: SessionActivity): SessionTreeSnapshot = SessionTreeSnapshot(
        root,
        listOf(SessionTreeNode(root, null, root.nativeId, rootActivity)) + children.values.map {
            SessionTreeNode(
                ref(it.call),
                it.parent?.let(::ref) ?: root,
                it.name,
                SessionActivity.entries.firstOrNull { state -> state.name == it.activity } ?: SessionActivity.Unknown,
            )
        },
        // Stream-json exposes observed agents, not an authoritative enumeration of all native CLI descendants.
        SessionTreeCoverage.Partial,
    )

    @Synchronized
    fun history(ref: SessionRef): ClaudeHistory? = children.keys.firstOrNull { ref(it) == ref }?.let(histories::get)

    /** Parent ids route both assistant output and tool results; root tool results remain on the root. */
    @Synchronized
    fun receive(message: JsonObject, turn: TurnId) {
        val owner = message.text("parent_tool_use_id")?.let { canonical(it) ?: it }
        val body = message["message"] as? JsonObject
        val parts = (body?.get("content") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (owner != null && owner in children) {
            val child = checkNotNull(children[owner])
            val frame = message.text("uuid")
            if (frame != null && frame in child.frames) return
            if (frame != null) children[owner] = child.copy(frames = (child.frames + frame).takeLastFrames())
            if (child.activity in ACTIVE || child.activity == "Unknown") {
                children[owner] = checkNotNull(children[owner]).copy(activity = "Running")
            }
            val journal = checkNotNull(histories[owner])
            val role = if (message.text("type") == "user") MessageRole.User else MessageRole.Assistant
            parts.forEach { part -> append(journal, turn, part, role) }
        }
        parts.forEach { discover(it, owner, turn) }
        if (message.text("type") == "system") taskEvent(message)
    }

    private fun discover(part: JsonObject, owner: String?, turn: TurnId) {
        if (part.text("type") == "tool_use" && part.text("name") in setOf("Agent", "Task")) {
            spawn(part, owner, turn)
        } else if (part.text("type") == "tool_result") {
            val call = part.text("tool_use_id")?.let(::canonical) ?: return
            val child = children[call] ?: return
            val isFailed = part.text("is_error") == "true"
            if (isFailed) {
                children[call] = child.copy(activity = "Failed")
            } else if (child.task == null && child.isBackground != true) {
                children[call] = child.copy(activity = if (child.isBackground == false) "Completed" else "Unknown")
            }
        }
    }

    private fun spawn(part: JsonObject, owner: String?, turn: TurnId) {
        val call = part.text("id") ?: return
        if ((owner != null && owner !in children) || call in children) return
        val input = part["input"] as? JsonObject
        children[call] = ClaudeChild(
            call,
            owner,
            input?.text("name") ?: input?.text("description") ?: input?.text("subagent_type") ?: call,
            isBackground = input?.text("run_in_background")?.toBooleanStrictOrNull(),
        )
        val journal = ClaudeHistory()
        histories[call] = journal
        val prompt = input?.text("prompt")
        if (prompt != null) {
            journal.item(turn) { SessionItem.Message(it, MessageRole.User, listOf(ContentPart.Text(prompt))) }
        }
    }

    private fun taskEvent(message: JsonObject) {
        val task = message.text("task_id")
        var call = message.text("tool_use_id")?.let(::canonical)
            ?: children.values.firstOrNull { task != null && it.task == task }?.call ?: return
        call = mergeAlias(call, task)
        val child = checkNotNull(children[call])
        val patch = message["patch"] as? JsonObject
        val status = patch?.text("status") ?: message.text("status")
        val activity = when (status) {
            "completed" -> "Completed"
            "failed" -> "Failed"
            "stopped", "killed", "cancelled" -> "Cancelled"
            "running" -> "Running"
            "pending", "queued" -> "Queued"
            "waiting_for_user", "awaiting_user" -> "AwaitingUser"
            else -> if (message.text("subtype") == "task_started") "Running" else child.activity
        }
        children[call] = child.copy(task = task ?: child.task, activity = activity)
    }

    private fun mergeAlias(call: String, task: String?): String {
        val existing = children.values.firstOrNull { task != null && it.task == task && it.call != call }
        if (existing != null) {
            val duplicate = checkNotNull(children.remove(call))
            histories.remove(call)?.let { journal ->
                checkNotNull(histories[existing.call]).absorb(journal.snapshot())
                journal.close()
            }
            children[existing.call] = existing.copy(aliases = existing.aliases + duplicate.aliases + call)
            children.replaceAll { _, child ->
                if (child.parent == call) child.copy(parent = existing.call) else child
            }
            return existing.call
        }

        return call
    }

    @Synchronized
    fun cancelled() {
        children.replaceAll { _, child ->
            if (child.activity in ACTIVE) child.copy(activity = "Cancelled") else child
        }
    }

    @Synchronized
    fun observationEnded() {
        children.replaceAll { _, child ->
            if (child.activity in ACTIVE) child.copy(activity = "Unknown") else child
        }
    }

    @Synchronized
    fun close() {
        histories.values.forEach { it.close() }
    }

    private fun append(history: ClaudeHistory, turn: TurnId, part: JsonObject, role: MessageRole) {
        val original = if (role == MessageRole.User) inputs[claudeInputKey(part)] else null
        if (original != null) {
            history.item(turn) { SessionItem.Message(it, role, original) }
            return
        }
        when (part.text("type")) {
            "text", "thinking" -> history.item(turn) {
                val content = if (part.text("type") == "thinking") {
                    ContentPart.Reasoning(part.text("thinking").orEmpty())
                } else {
                    ContentPart.Text(part.text("text").orEmpty())
                }
                SessionItem.Message(it, role, listOf(content))
            }

            "tool_use" -> history.item(turn) {
                SessionItem.ToolCall(
                    it,
                    ToolCallId(part.text("id").orEmpty()),
                    part.text("name").orEmpty(),
                    part["input"]?.toString().orEmpty(),
                    ToolCallStatus.Running,
                )
            }

            "tool_result" -> history.item(turn) {
                val text = part.text("content") ?: (part["content"] as? JsonArray).orEmpty()
                    .mapNotNull { value -> (value as? JsonObject)?.text("text") }.joinToString("\n")
                SessionItem.ToolResult(
                    it,
                    ToolCallId(part.text("tool_use_id").orEmpty()),
                    listOf(ContentPart.Text(text)),
                    if (part.text("is_error") == "true") EngineFailure.Unknown() else null,
                )
            }
        }
    }

    private fun canonical(call: String): String? =
        children[call]?.call ?: children.values.firstOrNull { call in it.aliases }?.call

    private fun ref(call: String): SessionRef = root.copy(nativeId = root.nativeId + "/agent/" + call)

    private companion object {
        val ACTIVE = setOf("Queued", "Running", "AwaitingUser")
    }
}

private fun Set<String>.takeLastFrames(): Set<String> = toList().takeLast(MAX_CHILD_FRAMES).toSet()
private const val MAX_CHILD_FRAMES = 512
