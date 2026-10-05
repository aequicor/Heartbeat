package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionActivity
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeNode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Metadata and history reads only: observing a child never runs thread/resume or changes its model. */
internal class CodexSessionTrees(private val runtime: CodexRuntime, private val rpc: CodexRpc) : SessionTrees {
    private val log = Log.tag("CodexSessionTrees")

    override fun observe(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot> = channelFlow {
        var previous = SessionTreeSnapshot(root)
        send(previous)
        val changes = Channel<Unit>(Channel.CONFLATED)
        val notifications = launch {
            runtime.treeChanges.collect { changes.trySend(Unit) }
        }
        val timer = launch {
            while (true) {
                changes.send(Unit)
                delay(2_000)
            }
        }
        try {
            for (ignored in changes) {
                previous = try {
                    snapshot(root, access)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "Codex session tree could not be refreshed" }
                    previous.copy(
                        nodes = previous.nodes.map { it.copy(activity = SessionActivity.Unknown) },
                        coverage = SessionTreeCoverage.Unavailable,
                    )
                }
                send(previous)
            }
        } finally {
            timer.cancel()
            notifications.cancel()
            changes.close()
        }
    }.flowOn(runtime.dispatchers.main)

    private suspend fun validate(root: SessionRef, access: SessionTreeAccess) {
        runtime.gate()
        if (root.engine != runtime.identity.engine || root.source != runtime.historySource ||
            access.target.engine != root.engine
        ) {
            fail(EngineFailure.Session(SessionFailureReason.NotFound))
        }
    }

    internal suspend fun snapshot(root: SessionRef, access: SessionTreeAccess): SessionTreeSnapshot {
        validate(root, access)
        val nodes = linkedMapOf<SessionRef, SessionTreeNode>()
        val pending = ArrayDeque<Pair<SessionRef, JsonObject>>()
        val queued = mutableSetOf<String>()
        pending.add(root to read(root))
        var isComplete = true
        while (pending.isNotEmpty()) {
            val (ref, thread) = pending.removeFirst()
            if (ref in nodes) continue
            val turns = readTurns(rpc, ref, thread)
            isComplete = isComplete && thread.text("historyMode") == "paginated" &&
                turns.all { it.text("itemsView") == "full" }
            val observed = activity(thread, turns.lastOrNull())
            val activity = if (observed == SessionActivity.Unknown &&
                ref.nativeId in queued
            ) {
                SessionActivity.Queued
            } else {
                observed
            }
            val node = thread.treeNode(root).copy(activity = activity)
            if (node.activity.isActive) {
                queued += turns.lastOrNull()?.array("items").orEmpty().asSequence().filterIsInstance<JsonObject>()
                    .flatMap { it.queuedAgents() }
            }
            nodes[ref] = node
            pending.addAll(children(root, ref, turns, nodes.keys))
        }
        return SessionTreeSnapshot(
            root,
            nodes.values.toList(),
            if (isComplete) SessionTreeCoverage.Complete else SessionTreeCoverage.Partial,
        )
    }

    private suspend fun children(
        root: SessionRef,
        parent: SessionRef,
        turns: List<JsonObject>,
        known: Set<SessionRef>,
    ): List<Pair<SessionRef, JsonObject>> {
        val ids = turns.asSequence().flatMap { it.array("items") }.filterIsInstance<JsonObject>()
            .flatMap { it.agentIds() }.distinct().toList()
        return ids.mapNotNull { id ->
            val ref = root.copy(nativeId = id)
            if (ref in known) return@mapNotNull null
            val thread = read(ref)
            // Forked histories include ancestor events; only the immediate native parent admits a child.
            if (thread.nativeParent() == parent.nativeId) ref to thread else null
        }
    }

    private fun activity(thread: JsonObject, latest: JsonObject?): SessionActivity {
        val state = thread.treeNode(
            SessionRef(runtime.identity.engine, runtime.historySource, checkNotNull(thread.text("id"))),
        ).activity
        if (state.isActive || state == SessionActivity.Failed) return state
        return when (latest?.text("status")) {
            "completed" -> SessionActivity.Completed
            "interrupted" -> SessionActivity.Cancelled
            "failed" -> SessionActivity.Failed
            else -> state
        }
    }

    override suspend fun history(root: SessionRef, ref: SessionRef, access: SessionTreeAccess): SessionHistory {
        val family = snapshot(root, access)
        if (ref != root && family.descendants().none { it.node.ref == ref }) {
            fail(EngineFailure.Session(SessionFailureReason.NotFound))
        }
        return CodexTreeHistory(runtime, rpc, root, ref) { validate(root, access) }
    }

    private suspend fun read(ref: SessionRef): JsonObject {
        val thread = rpc.request("thread/read", json("threadId" to ref.nativeId.json())).obj("thread")
        if (thread.text("id") != ref.nativeId) protocolFailure()
        return thread
    }
}

internal fun JsonObject.nativeParent(): String? = text("parentThreadId")
    ?: (
        ((this["source"] as? JsonObject)?.let { it["subAgent"] ?: it["subagent"] } as? JsonObject)
            ?.get("thread_spawn") as? JsonObject
    )?.text("parent_thread_id")

internal fun JsonObject.treeNode(root: SessionRef): SessionTreeNode {
    val id = text("id") ?: protocolFailure()
    val status = this["status"] as? JsonObject
    val flags = (status?.get("activeFlags") as? JsonArray).orEmpty().map { it.toString().trim('"') }
    val activity = when (status?.text("type")) {
        "active" -> if (flags.any { it == "waitingOnApproval" || it == "waitingOnUserInput" }) {
            SessionActivity.AwaitingUser
        } else {
            SessionActivity.Running
        }

        "idle" -> SessionActivity.Idle

        "systemError" -> SessionActivity.Failed

        else -> SessionActivity.Unknown
    }
    return SessionTreeNode(
        root.copy(nativeId = id),
        nativeParent()?.let { root.copy(nativeId = it) },
        text("name")?.takeIf { it.isNotBlank() } ?: text("agentNickname")?.takeIf { it.isNotBlank() } ?: id,
        activity,
    )
}

/** A private replay journal, refreshed by read requests; owns no execution lease. */
private class CodexTreeHistory(
    private val runtime: CodexRuntime,
    private val rpc: CodexRpc,
    private val root: SessionRef,
    private val ref: SessionRef,
    private val gate: suspend () -> Unit,
) : SessionHistory {
    private val history = CodexHistory()

    override suspend fun page(request: HistoryPageRequest): HistoryPage =
        kotlinx.coroutines.withContext(runtime.dispatchers.main) {
            refresh()
            history.page(request)
        }

    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = channelFlow {
        val updates = launch { history.watch(after).collect { send(it) } }
        try {
            while (true) {
                delay(HISTORY_REFRESH_MILLIS)
                refresh()
            }
        } finally {
            updates.cancel()
        }
    }.flowOn(runtime.dispatchers.main)

    private suspend fun refresh() {
        gate()
        val thread = rpc.request(
            "thread/read",
            json("threadId" to ref.nativeId.json()),
        ).obj("thread")
        if (thread.text("id") != ref.nativeId) protocolFailure()
        val turns = readTurns(rpc, ref, thread)
        for (turn in turns) {
            val id = TurnId(turn.text("id") ?: protocolFailure())
            (runtime.host.resourceHistory.parts(ref, id.value) ?: runtime.host.resourceHistory.parts(root, id.value))
                ?.let { history.rememberOriginals(id, it) }
            for (item in turn.array("items")) {
                val native = item as? JsonObject ?: protocolFailure()
                if (!history.matches(native)) history.nativeItem(native, id)
            }
        }
        history.seeded(isComplete = turns.all { it.text("itemsView") in setOf(null, "full") })
    }
}

private const val HISTORY_REFRESH_MILLIS = 1_000L

internal fun JsonObject.isTreeChange(): Boolean {
    val method = text("method").orEmpty()
    return method.startsWith("thread/") || method.startsWith("turn/") || method == "item/completed"
}

/** Canonical native replay discovers children even when thread/list intentionally omits subagents. */
private suspend fun readTurns(rpc: CodexRpc, ref: SessionRef, thread: JsonObject): List<JsonObject> {
    if (thread.text("historyMode") != "paginated") {
        return rpc.request(
            "thread/read",
            json("threadId" to ref.nativeId.json(), "includeTurns" to JsonPrimitive(true)),
        )
            .obj("thread").array("turns").map { it as? JsonObject ?: protocolFailure() }
    }
    val turns = mutableListOf<JsonObject>()
    var cursor: String? = null
    val seen = mutableSetOf<String>()
    do {
        val response = rpc.request(
            "thread/turns/list",
            buildJsonObject {
                put("threadId", ref.nativeId)
                put("sortDirection", "asc")
                put("itemsView", "full")
                put("limit", 100)
                put("cursor", cursor?.json() ?: JsonNull)
            },
        )
        turns += response.array("data").map { it as? JsonObject ?: protocolFailure() }
        cursor = response.text("nextCursor")
        if (cursor != null && !seen.add(cursor)) protocolFailure()
    } while (cursor != null)
    return turns
}

internal fun JsonObject.agentIds(): List<String> = when (text("type")) {
    "subAgentActivity" -> listOfNotNull(text("agentThreadId"))

    "collabAgentToolCall" -> array("receiverThreadIds").mapNotNull {
        (it as? JsonPrimitive)?.content
    }

    else -> emptyList()
}

internal fun JsonObject.queuedAgents(): List<String> = (this["agentsStates"] as? JsonObject).orEmpty().filterValues {
    (it as? JsonObject)?.text("status") == "pendingInit"
}.keys.toList()
