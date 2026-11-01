package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionActivity
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeNode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * Read-only native session family from the DSH on-disk store. Discovery reads only each session's bounded header,
 * so the family is authoritative for persisted sessions; activity is not recorded in the header and stays unknown.
 * Missing capability is never reported as a confirmed empty family: an unreadable store is unavailable coverage.
 */
internal class DeepSeekSessionTrees(
    private val nativeHistory: DeepSeekNativeHistory,
    private val scan: suspend () -> Map<String, DeepSeekFamily> = { nativeHistory.families() },
) : SessionTrees {
    private val log = Log.tag("DeepSeekSessionTrees")

    override fun observe(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot> = flow {
        while (true) {
            emit(readSnapshot(root, access))
            delay(TREE_REFRESH_MILLIS)
        }
    }.distinctUntilChanged()

    override suspend fun history(root: SessionRef, ref: SessionRef, access: SessionTreeAccess): SessionHistory {
        val family = readSnapshot(root, access)
        if (ref != root && family.coverage == SessionTreeCoverage.Unavailable) {
            // An unreadable store cannot prove that a member is absent; report the read failure instead.
            throw EngineException(EngineFailure.History(HistoryFailureReason.Unavailable))
        }
        if (ref != root && family.descendants().none { it.node.ref == ref }) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        }
        return nativeHistory.stored(ref)
    }

    internal suspend fun snapshot(root: SessionRef, access: SessionTreeAccess): SessionTreeSnapshot =
        readSnapshot(root, access)

    private suspend fun readSnapshot(root: SessionRef, access: SessionTreeAccess): SessionTreeSnapshot {
        validate(root, access)
        return try {
            discover(root)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "DeepSeek Harness session tree could not be read" }
            SessionTreeSnapshot(root, coverage = SessionTreeCoverage.Unavailable)
        }
    }

    private fun validate(root: SessionRef, access: SessionTreeAccess) {
        if (root.engine != DeepSeekEngine.Id || root.source != DeepSeekEngine.SessionSource ||
            access.target.engine != DeepSeekEngine.Id
        ) {
            throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))
        }
    }

    /** Cycle-safe depth-first walk from [root] over the store's `parentSession` links. */
    private suspend fun discover(root: SessionRef): SessionTreeSnapshot {
        val families = scan()
        val children = families.entries
            .filter { it.value.parent != null }
            .groupBy({ it.value.parent }, { it.key })
        val nodes = mutableListOf<SessionTreeNode>()
        val visited = mutableSetOf(root.nativeId)
        val pending = ArrayDeque<String>().apply { add(root.nativeId) }
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            for (id in children[parent].orEmpty()) {
                if (!visited.add(id)) continue
                nodes += SessionTreeNode(
                    root.copy(nativeId = id),
                    root.copy(nativeId = parent),
                    families[id]?.name ?: id,
                    SessionActivity.Unknown,
                )
                pending.add(id)
            }
        }
        val rootName = families[root.nativeId]?.name ?: root.nativeId
        return SessionTreeSnapshot(
            root,
            listOf(SessionTreeNode(root, null, rootName, SessionActivity.Unknown)) + nodes,
            SessionTreeCoverage.Partial,
        )
    }
}

private const val TREE_REFRESH_MILLIS = 2_000L
