package io.aequicor.heartbeat.feature.aiengine.koog.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** One atomic transcript checkpoint. An unfinished turn is recovered as Unknown after runtime loss. */
@Serializable
internal data class KoogRecord(
    val summary: SessionSummary,
    val model: ModelId,
    val items: List<SessionItem> = emptyList(),
    val lastTurn: Turn? = null,
    val coverage: HistoryCoverage = HistoryCoverage.Complete,
)

internal interface KoogSessionRecords {
    suspend fun list(): List<KoogRecord>
    suspend fun get(ref: SessionRef): KoogRecord?
    suspend fun save(record: KoogRecord)
}

private val SessionsSpec = KeyValueSpec("ai_koog_sessions")
private val SessionsKey = jsonKey("sessions", serializer<List<KoogRecord>>())

/** Atomic profile snapshot; a database can replace this implementation without changing native ids. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredKoogSessionRecords(
    @ForScope(ProfileScope::class) stores: DataStores,
) : KoogSessionRecords {
    private val store = stores.keyValue(SessionsSpec)
    private val mutex = Mutex()

    override suspend fun list(): List<KoogRecord> = store.get(SessionsKey).orEmpty()
    override suspend fun get(ref: SessionRef): KoogRecord? = list().firstOrNull { it.summary.ref == ref }

    override suspend fun save(record: KoogRecord) {
        mutex.withLock {
            store.set(SessionsKey, list().filterNot { it.summary.ref == record.summary.ref } + record)
        }
    }
}
