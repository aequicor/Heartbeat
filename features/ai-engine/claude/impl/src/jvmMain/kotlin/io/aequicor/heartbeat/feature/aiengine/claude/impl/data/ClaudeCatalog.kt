package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.Serializable
import java.util.UUID

/** Native launch identity and bounded observed history; no CLI credentials or MCP bearer are persisted. */
@Serializable
internal data class ClaudeRecord(
    val ref: SessionRef,
    val route: ExecutionRoute,
    val target: EngineTarget,
    val launch: ClaudeLaunch = ClaudeLaunch.Prepared,
    val lastTurn: Turn? = null,
    val activeTurn: Turn? = null,
    val history: ClaudeHistorySnapshot = ClaudeHistorySnapshot(),
    val nativeStore: String = "default",
    val undelivered: TurnId? = null,
)

/** An attempted process may have saved a transcript even when no output frame reached the host. */
@Serializable
internal enum class ClaudeLaunch { Prepared, Attempted, Confirmed }

/** Stable item positions and replay generation survive release, eviction and profile restart. */
@Serializable
internal data class ClaudeHistorySnapshot(
    val generation: String = UUID.randomUUID().toString(),
    val sequence: Long = 0,
    val position: Long = 0,
    val items: List<SessionItem> = emptyList(),
    val events: List<Pair<Long, SessionEvent>> = emptyList(),
)

/** Profile-owned catalog of conversations created by this adapter. */
internal interface ClaudeCatalog {
    suspend fun find(ref: SessionRef): ClaudeRecord?
    suspend fun save(record: ClaudeRecord)
}

private val ClaudeCatalogSpec = KeyValueSpec("claude_sessions")

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredClaudeCatalog(
    @ForScope(ProfileScope::class) stores: DataStores,
) : ClaudeCatalog {
    private val log = Log.tag("ClaudeCatalog")
    private val store = stores.keyValue(ClaudeCatalogSpec)

    override suspend fun find(ref: SessionRef): ClaudeRecord? {
        log.d { "Read saved Claude session" }
        val key = key(ref) ?: return null
        return store.get(key)?.takeIf { it.ref == ref }
    }

    override suspend fun save(record: ClaudeRecord) {
        log.d { "Persist Claude session launch=${record.launch}" }
        store.set(checkNotNull(key(record.ref)), record)
    }

    private fun key(ref: SessionRef): StoreKey<ClaudeRecord>? = if (NATIVE_UUID.matches(ref.nativeId)) {
        jsonKey<ClaudeRecord>("session_${ref.nativeId}", ClaudeRecord.serializer())
    } else {
        null
    }
}

private val NATIVE_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
