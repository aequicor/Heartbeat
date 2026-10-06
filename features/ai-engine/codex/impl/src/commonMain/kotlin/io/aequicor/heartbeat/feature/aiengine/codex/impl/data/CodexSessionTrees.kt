package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Metadata and history reads only: observing a child never runs thread/resume or changes its model.
 * Complete, inactive families wait for runtime notifications; active or uncertain families retain polling.
 */
internal class CodexSessionTrees(private val runtime: CodexRuntime, private val rpc: CodexRpc) : SessionTrees {
    private val log = Log.tag("CodexSessionTrees")

    @HighFrequency
    override fun observe(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot> = channelFlow {
        var previous = SessionTreeSnapshot(root)
        send(previous)
        runtime.refreshOnChanges(TREE_REFRESH_MILLIS) {
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
            previous.coverage != SessionTreeCoverage.Complete || previous.nodes.any { it.activity.isPollingRequired }
        }
    }.flowOn(runtime.dispatchers.main)

    @HighFrequency
    private suspend fun validate(root: SessionRef, access: SessionTreeAccess) {
        runtime.gate()
        if (root.engine != runtime.identity.engine || root.source != runtime.historySource ||
            access.target.engine != root.engine
        ) {
            fail(EngineFailure.Session(SessionFailureReason.NotFound))
        }
    }

    @HighFrequency
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
            val replay = readTurns(rpc, ref, thread)
            val turns = replay.turns
            isComplete = isComplete && replay.isCanonical
            val observed = thread.activity(root, turns.lastOrNull())
            val activity = if (observed == SessionActivity.Unknown &&
                ref.nativeId in queued
            ) {
                SessionActivity.Queued
            } else {
                observed
            }
            val node = thread.treeNode(root).copy(activity = activity)
            if (node.activity.isActive) {
                queued += (turns.lastOrNull()?.get("items") as? JsonArray).orEmpty()
                    .asSequence().filterIsInstance<JsonObject>()
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

    @HighFrequency
    private suspend fun children(
        root: SessionRef,
        parent: SessionRef,
        turns: List<JsonObject>,
        known: Set<SessionRef>,
    ): List<Pair<SessionRef, JsonObject>> {
        val ids = turns.asSequence().flatMap { (it["items"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
            .flatMap { it.agentIds() }.distinct().toList()
        return ids.mapNotNull { id ->
            val ref = root.copy(nativeId = id)
            if (ref in known) return@mapNotNull null
            val thread = read(ref)
            // Forked histories include ancestor events; only the immediate native parent admits a child.
            if (thread.nativeParent() == parent.nativeId) ref to thread else null
        }
    }

    override suspend fun history(root: SessionRef, ref: SessionRef, access: SessionTreeAccess): SessionHistory {
        val family = snapshot(root, access)
        if (ref != root && family.descendants().none { it.node.ref == ref }) {
            fail(EngineFailure.Session(SessionFailureReason.NotFound))
        }
        return CodexTreeHistory(runtime, rpc, root, ref) { validate(root, access) }
    }

    @HighFrequency
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

    @HighFrequency
    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = channelFlow {
        val updates = launch { history.watch(after).collect { send(it) } }
        try {
            runtime.refreshOnChanges(HISTORY_REFRESH_MILLIS) { refresh() }
        } finally {
            updates.cancel()
        }
    }.flowOn(runtime.dispatchers.main)

    /** Returns whether activity or incomplete replay still requires fallback polling. */
    @HighFrequency
    private suspend fun refresh(): Boolean {
        gate()
        val thread = rpc.request(
            "thread/read",
            json("threadId" to ref.nativeId.json()),
        ).obj("thread")
        if (thread.text("id") != ref.nativeId) protocolFailure()
        val replay = readTurns(rpc, ref, thread)
        val activity = thread.activity(root, replay.turns.lastOrNull())
        history.seeded(isComplete = replay.isCanonical)
        // Legacy replay synthesizes item IDs and omits tools. Even Partial replay could replace a richer
        // stored suffix, so only a canonical full snapshot may add items to this private journal.
        if (!replay.isCanonical) return true
        for (turn in replay.turns) {
            val id = TurnId(turn.text("id") ?: protocolFailure())
            (runtime.host.resourceHistory.parts(ref, id.value) ?: runtime.host.resourceHistory.parts(root, id.value))
                ?.let { history.rememberOriginals(id, it) }
            for (item in turn.array("items")) {
                val native = item as? JsonObject ?: protocolFailure()
                if (!history.matches(native)) history.nativeItem(native, id)
            }
        }
        return activity.isPollingRequired
    }
}

private const val TREE_REFRESH_MILLIS = 2_000L
private const val HISTORY_REFRESH_MILLIS = 1_000L

/** Idle readers stay subscribed for a new turn; active or uncertain readers retain fallback polling. */
@HighFrequency
private suspend fun CodexRuntime.refreshOnChanges(intervalMillis: Long, refresh: suspend () -> Boolean): Unit =
    coroutineScope {
        val changes = Channel<Unit>(Channel.CONFLATED)
        // Subscribe before the first read so an invalidation arriving during it cannot be lost.
        val notifications = launch(start = CoroutineStart.UNDISPATCHED) {
            treeChanges.collect { changes.trySend(Unit) }
        }
        try {
            while (true) {
                val isPollingRequired = refresh()
                // A closed runtime has no future notifications; let callers reacquire a live reader.
                if (isClosed) break
                if (isPollingRequired) {
                    withTimeoutOrNull(intervalMillis) { changes.receive() }
                } else {
                    changes.receive()
                }
            }
        } finally {
            notifications.cancel()
            changes.close()
        }
    }

private val SessionActivity.isPollingRequired: Boolean get() = isActive || this == SessionActivity.Unknown

private fun JsonObject.activity(root: SessionRef, latest: JsonObject?): SessionActivity {
    val state = treeNode(root).activity
    // A saved terminal turn cannot prove that an unloaded thread is still inactive.
    if (state.isActive || state == SessionActivity.Failed || state == SessionActivity.Unknown) return state
    return when (latest?.text("status")) {
        "completed" -> SessionActivity.Completed
        "interrupted" -> SessionActivity.Cancelled
        "failed" -> SessionActivity.Failed
        else -> state
    }
}

internal fun JsonObject.isTreeChange(): Boolean {
    val method = text("method").orEmpty()
    return method.startsWith("thread/") || method.startsWith("turn/") || method == "item/completed"
}

/** Canonical native replay discovers children even when thread/list intentionally omits subagents. */
@HighFrequency
private suspend fun readTurns(rpc: CodexRpc, ref: SessionRef, thread: JsonObject): TreeReplay {
    if (thread.text("historyMode") != "paginated") {
        val turns = rpc.request(
            "thread/read",
            json("threadId" to ref.nativeId.json(), "includeTurns" to JsonPrimitive(true)),
        )
            .obj("thread").array("turns").map { it as? JsonObject ?: protocolFailure() }
        return TreeReplay(turns, isCanonical = false)
    }
    val turns = mutableListOf<JsonObject>()
    var hasPendingItems = false
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
        hasPendingItems = hasPendingItems || response.text("itemsBackwardsCursor") != null
        cursor = response.text("nextCursor")
        if (cursor != null && !seen.add(cursor)) protocolFailure()
    } while (cursor != null)
    val isCanonical = !hasPendingItems && turns.isNotEmpty() && turns.all {
        it.text("itemsView") == "full" && it["items"] is JsonArray && it.text("itemsBackwardsCursor") == null
    }
    return TreeReplay(turns, isCanonical)
}

private data class TreeReplay(val turns: List<JsonObject>, val isCanonical: Boolean)

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
