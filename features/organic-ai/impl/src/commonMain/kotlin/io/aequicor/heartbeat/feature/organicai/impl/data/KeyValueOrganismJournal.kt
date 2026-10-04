package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.datastore.stringSetKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganismJournal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days

/**
 * Organisms of the profile, one raw JSON record per organism plus an index of ids. A record that cannot be decoded
 * (damaged, or incompatible with this version) is skipped and kept rather than read as absent, until an organism with
 * the same id is written; a record whose retention expired is dropped from the index. Writes are serialized and never
 * replace a newer version; JSON is encoded off the main thread. Texts are never logged.
 */
@ContributesBinding(ProfileScope::class)
@SingleIn(ProfileScope::class)
@Inject
internal class KeyValueOrganismJournal(
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val dispatchers: DispatcherProvider,
) : OrganismJournal {
    private val log = Log.tag("KeyValueOrganismJournal")
    private val store by lazy { stores.keyValue(SPEC) }
    private val mutex = Mutex()

    /** Last version written or read per organism; guarded by [mutex]. */
    private val versions = mutableMapOf<OrganismId, Long>()

    override suspend fun load(): List<Organism> = mutex.withLock {
        val ids = store.get(INDEX).orEmpty()
        val records = ids.associateWith { store.get(record(it)) }
        val expired = records.filterValues { it == null }.keys
        val organisms = withContext(dispatchers.default) {
            records.mapNotNull { (id, raw) -> raw?.let { decode(id, it) } }
        }
        if (expired.isNotEmpty()) store.set(INDEX, ids - expired)
        organisms.forEach { versions[it.id] = it.version }
        log.d {
            "loaded ${organisms.size} organisms, ${records.size - expired.size - organisms.size} unreadable, " +
                "${expired.size} expired"
        }
        organisms
    }

    override suspend fun save(organism: Organism): Unit = mutex.withLock {
        val known = versions[organism.id]
        if (known != null && known >= organism.version) {
            log.v { "organism ${organism.id.value} version ${organism.version} skipped: $known is stored" }
            return@withLock
        }
        val retention = if (organism.isDeveloping) Retention.Permanent else Retention.expiring(Expiry.After(KEEP_ENDED))
        val raw = withContext(dispatchers.default) { json.encodeToString(Organism.serializer(), organism) }
        store.set(record(organism.id.value), raw, retention)
        val ids = store.get(INDEX).orEmpty()
        if (organism.id.value !in ids) store.set(INDEX, ids + organism.id.value)
        versions[organism.id] = organism.version
        log.v { "organism ${organism.id.value} version ${organism.version} saved" }
    }

    private fun decode(id: String, raw: String): Organism? = try {
        json.decodeFromString(Organism.serializer(), raw)
    } catch (e: IllegalArgumentException) {
        // Decoding messages quote the record, so only the failure kind is logged.
        log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "organism $id is unreadable and kept" }
        null
    }

    private companion object {
        val SPEC = KeyValueSpec("organic_ai_organisms")
        val INDEX = stringSetKey("index")
        val KEEP_ENDED = 30.days
        val json = Json { ignoreUnknownKeys = true }

        fun record(id: String) = stringKey("organism.$id")
    }
}
