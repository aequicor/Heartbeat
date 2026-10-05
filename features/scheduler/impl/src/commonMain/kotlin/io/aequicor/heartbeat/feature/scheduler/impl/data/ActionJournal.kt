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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** A started action; [payload] keeps its completed result until the matching wakes settle. Never logged. */
@Serializable
internal data class ActionRecord(
    val id: ActionId,
    val kind: String,
    val startedAt: Instant,
    val payload: String? = null,
) {
    override fun toString(): String = "ActionRecord(id=$id, kind=$kind, completed=${payload != null})"
}

/** Running actions of the profile, so a restart can report the ones it interrupted. */
internal interface ActionJournal {
    /** Records a started action or replaces it with its completed result. */
    suspend fun add(record: ActionRecord)

    /** Forgets a finished action. */
    suspend fun remove(id: ActionId)

    /** Reads running actions and completed results without acknowledging their delivery. */
    suspend fun readAll(): List<ActionRecord>
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

    override suspend fun add(record: ActionRecord) = lock.withLock {
        write(read().filterNot { it.id == record.id } + record)
    }

    override suspend fun remove(id: ActionId) = lock.withLock { write(read().filterNot { it.id == id }) }

    override suspend fun readAll(): List<ActionRecord> = lock.withLock { read() }

    private suspend fun read(): List<ActionRecord> {
        val raw = store.get(ACTIONS) ?: return emptyList()
        return try {
            json.decodeFromString(RECORDS, raw)
        } catch (e: IllegalArgumentException) {
            // Decoder messages can contain the stored result; preserve only the failure kind.
            throw e.withoutActionText()
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

/** Decoder messages and causes may quote stored action results; only the failure kind may leave this layer. */
private fun IllegalArgumentException.withoutActionText(): IllegalStateException =
    IllegalStateException("Unreadable scheduler actions (${this::class.simpleName.orEmpty()})")
