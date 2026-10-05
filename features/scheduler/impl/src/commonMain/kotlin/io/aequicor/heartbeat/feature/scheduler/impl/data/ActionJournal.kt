package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** A background action that has started and not reported its result yet. Holds no command or prompt text. */
@Serializable
internal data class ActionRecord(val id: ActionId, val kind: String, val startedAt: Instant)

/** Running actions of the profile, so a restart can report the ones it interrupted. */
internal interface ActionJournal {
    /** Records a started action. */
    suspend fun add(record: ActionRecord)

    /** Forgets a finished action. */
    suspend fun remove(id: ActionId)

    /** Returns and forgets every recorded action. */
    suspend fun takeAll(): List<ActionRecord>
}

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueActionJournal(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : ActionJournal {
    private val log = Log.tag("KeyValueActionJournal")
    private val store by lazy { stores.keyValue(SPEC) }
    private val lock = Mutex()

    override suspend fun add(record: ActionRecord) = lock.withLock { write(read() + record) }

    override suspend fun remove(id: ActionId) = lock.withLock { write(read().filterNot { it.id == id }) }

    override suspend fun takeAll(): List<ActionRecord> = lock.withLock { read().also { write(emptyList()) } }

    private suspend fun read(): List<ActionRecord> {
        val raw = store.get(ACTIONS) ?: return emptyList()
        return try {
            json.decodeFromString(RECORDS, raw)
        } catch (e: SerializationException) {
            // Records hold only ids, kinds and times; an unreadable journal only loses interruption reports.
            log.w(e) { "unreadable action journal, dropped" }
            emptyList()
        }
    }

    private suspend fun write(records: List<ActionRecord>) {
        log.v { "write running actions: ${records.size}" }
        if (records.isEmpty()) store.remove(ACTIONS) else store.set(ACTIONS, json.encodeToString(RECORDS, records))
    }

    private companion object {
        val SPEC = KeyValueSpec("scheduler")
        val ACTIONS = stringKey("actions")
        val RECORDS = ListSerializer(ActionRecord.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}
