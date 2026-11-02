package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.datastore.stringSetKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicExecutorAddress
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeAttempt
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeClaim
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeStage
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganismJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.isAdmission
import io.aequicor.heartbeat.feature.organicai.impl.domain.requireProgress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.days

/**
 * Organisms of the profile, one raw JSON record per organism plus an index of ids. A record that cannot be decoded
 * (damaged, or incompatible with this version) is skipped and kept rather than read as absent, until an organism with
 * the same id is explicitly repaired; a record whose retention expired is dropped from the index. Writes are
 * serialized and never
 * replace a newer version; JSON is encoded off the main thread. Texts are never logged.
 */
@ContributesBinding(ProfileScope::class, binding = binding<OrganismJournal>())
@ContributesBinding(ProfileScope::class, binding = binding<OrganicNativeJournal>())
@SingleIn(ProfileScope::class)
@Inject
internal class KeyValueOrganismJournal(
    @ForScope(ProfileScope::class) private val stores: DataStores,
    private val dispatchers: DispatcherProvider,
) : OrganismJournal,
    OrganicNativeJournal {
    private val log = Log.tag("KeyValueOrganismJournal")
    private val store by lazy {
        stores.keyValue(SPEC).also {
            check(it.spec.readPolicy == KeyValueReadPolicy.Strict) { "Organism authority requires strict storage" }
        }
    }
    private val mutex = Mutex()

    override suspend fun load(): List<Organism> = mutex.withLock {
        val ids = store.get(INDEX).orEmpty()
        val records = ids.associateWith { store.get(record(it)) }
        val expired = records.filterValues { it == null }.keys
        val organisms = withContext(dispatchers.default) {
            records.mapNotNull { (id, raw) -> raw?.let { decode(id, it) } }
        }
        if (expired.isNotEmpty()) store.set(INDEX, ids - expired)
        log.d {
            "loaded ${organisms.size} organisms, ${records.size - expired.size - organisms.size} unreadable, " +
                "${expired.size} expired"
        }
        organisms
    }

    override suspend fun read(id: OrganismId): Organism? = mutex.withLock { readRecord(id)?.restored() }

    override suspend fun save(organism: Organism): Unit = mutex.withLock {
        val existing = readRecord(organism.id)
        check(existing == null || existing.organism.incarnation == organism.incarnation) {
            "An existing organism incarnation cannot be replaced"
        }
        val known = existing?.organism?.version
        if (known != null && known >= organism.version) {
            log.v { "organism ${organism.id.value} version ${organism.version} skipped: $known is stored" }
            return@withLock
        }
        write((existing ?: OrganicJournalRecord(organism)).copy(organism = organism, isReleaseProven = false))
    }

    override suspend fun isTracked(id: OrganismId, incarnation: String): Boolean = mutex.withLock {
        requireRecord(id, incarnation).isNativeTracked
    }

    override suspend fun attempts(id: OrganismId, incarnation: String): List<OrganicNativeAttempt> = mutex.withLock {
        requireRecord(id, incarnation).attempts
    }

    override suspend fun reserve(
        id: OrganismId,
        incarnation: String,
        claim: OrganicNativeClaim,
    ): OrganicNativeAttempt = mutex.withLock {
        val record = requireRecord(id, incarnation)
        check(record.isNativeTracked) { "Legacy native work requires explicit reconciliation" }
        val address = OrganicExecutorAddress.parse(claim.address)
        check(address.permits(record.organism, claim.execution)) { "Native claim has no active canonical executor" }
        val previous = record.attempts.singleOrNull { it.claim.token == claim.token }
        if (previous != null) {
            check(previous.claim == claim) { "Native claim payload cannot change" }
            return@withLock previous
        }
        check(address.permitsRequest(record.organism, claim.request)) { "Native claim request is obsolete" }
        check(record.attempts.none { it.claim.request == claim.request }) { "Native request already has a claim" }
        check(record.attempts.none { it.claim.address == claim.address && !it.isQuiescent }) {
            "Earlier execution is still unresolved"
        }
        val receipt = OrganicNativeAttempt(claim)
        write(record.copy(attempts = record.attempts + receipt, isReleaseProven = false))
        receipt
    }

    override suspend fun advance(
        id: OrganismId,
        incarnation: String,
        expected: OrganicNativeAttempt,
        next: OrganicNativeAttempt,
    ): OrganicNativeAttempt = mutex.withLock {
        val record = requireRecord(id, incarnation)
        val current = checkNotNull(record.attempts.singleOrNull { it.claim.token == expected.claim.token }) {
            "Native claim is missing"
        }
        if (current == next) {
            check(!next.stage.isAdmission) { "Native request entry was already consumed" }
            return@withLock current
        }
        check(current == expected && next.claim == expected.claim && next.revision == expected.revision + 1) {
            "Native claim revision or immutable payload changed"
        }
        expected.requireProgress(next)
        if (next.stage in setOf(OrganicNativeStage.Opening, OrganicNativeStage.Entered, OrganicNativeStage.Sending)) {
            val address = OrganicExecutorAddress.parse(next.claim.address)
            check(address.permits(record.organism, next.claim.execution)) { "Executor ended before native entry" }
            check(address.permitsRequest(record.organism, next.claim.request)) { "Native request was superseded" }
        }
        write(record.copy(attempts = record.attempts.map { if (it == current) next else it }, isReleaseProven = false))
        next
    }

    override suspend fun released(
        id: OrganismId,
        incarnation: String,
        version: Long,
        references: Set<SessionRef>,
    ): Unit = mutex.withLock {
        val record = requireRecord(id, incarnation)
        check(record.isNativeTracked) { "Legacy native absence cannot prove release" }
        check(record.organism.version == version && !record.organism.isDeveloping) { "Release snapshot changed" }
        val declared = record.organism.cells.mapNotNull { it.session } + record.organism.trials.mapNotNull { it.judge }
        val proven = record.attempts.filter { it.isQuiescent }.mapNotNull { it.session }.toSet()
        check(record.attempts.all { it.isQuiescent } && (references + declared).all { it in proven }) {
            "Native release lacks exact quiescence evidence"
        }
        write(record.copy(isReleaseProven = true))
    }

    private suspend fun requireRecord(id: OrganismId, incarnation: String): OrganicJournalRecord {
        val record = checkNotNull(readRecord(id)) { "Canonical organism is missing" }
        check(record.organism.incarnation == incarnation) { "Organism incarnation changed" }
        return record
    }

    private suspend fun readRecord(id: OrganismId): OrganicJournalRecord? {
        val raw = store.get(record(id.value)) ?: return null
        return withContext(dispatchers.default) { decodeOrganicRecord(raw) }.also {
            require(it.organism.id == id) { "Organism record key does not match its identity" }
        }
    }

    private suspend fun write(value: OrganicJournalRecord) {
        val organism = value.organism
        val retention = if (!organism.isDeveloping && value.isReleaseProven) {
            Retention.expiring(Expiry.After(KEEP_ENDED))
        } else {
            Retention.Permanent
        }
        val raw = withContext(dispatchers.default) {
            OrganicJournalJson.encodeToString(OrganicJournalRecord.serializer(), value)
        }
        val ids = store.get(INDEX).orEmpty()
        if (organism.id.value !in ids) store.set(INDEX, ids + organism.id.value)
        store.set(record(organism.id.value), raw, retention)
        log.v { "organism snapshot and native receipts committed" }
    }

    private fun decode(id: String, raw: String): Organism? = try {
        decodeOrganicRecord(raw).restored().also {
            require(it.id.value == id) { "Organism record key does not match its identity" }
        }
    } catch (e: IllegalArgumentException) {
        log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "organism $id is unreadable and kept" }
        null
    }

    private companion object {
        val SPEC = KeyValueSpec("organic_ai_organisms", readPolicy = KeyValueReadPolicy.Strict)
        val INDEX = stringSetKey("index")
        val KEEP_ENDED = 30.days

        fun record(id: String) = stringKey("organism.$id")
    }
}
