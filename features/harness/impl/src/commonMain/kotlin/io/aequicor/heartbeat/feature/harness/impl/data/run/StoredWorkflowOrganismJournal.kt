package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessKeyValueJournal
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessRawValue
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageConflict
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageException
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismAdmission
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowOrganismGrant
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowOrganismJournal
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.admissionFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock

/** Small permanent aggregate bounded by the organism quotas, with a durable commit marker and strict decoding. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class StoredWorkflowOrganismJournal(private val store: KeyValueStore, clock: Clock) :
    WorkflowOrganismJournal {
    @Inject
    constructor(
        @ForScope(ProfileScope::class) stores: DataStores,
        clock: Clock,
    ) : this(stores.keyValue(KeyValueSpec("harness_workflow_organisms")), clock)

    private val lock = Mutex()
    private val journal = HarnessKeyValueJournal(store, clock)
    private val log = Log.tag("HarnessWorkflow")
    private val key = stringKey("grants")

    override suspend fun acquire(grant: WorkflowOrganismGrant): OrganismAdmission {
        var admission = OrganismAdmission.Granted
        update { records ->
            val old = records.singleOrNull { it.run == grant.run && it.key == grant.key }
            when {
                old == grant -> records

                old != null || records.any { it.organism == grant.organism } -> throw HarnessStorageConflict()

                else -> {
                    admission = admissionFor(records, grant)
                    if (admission == OrganismAdmission.Granted) records + grant else records
                }
            }
        }
        return admission
    }

    override suspend fun pending(): List<WorkflowOrganismGrant> = access {
        journal.recover()
        decode(store.get(key))
    }

    override suspend fun settled(grant: WorkflowOrganismGrant) = update { records ->
        val old = records.singleOrNull { it.run == grant.run && it.key == grant.key }
        if (old != null && old != grant) throw HarnessStorageConflict()
        records.filterNot { it.run == grant.run && it.key == grant.key }
    }

    private suspend fun update(transform: (List<WorkflowOrganismGrant>) -> List<WorkflowOrganismGrant>) = access {
        journal.recover()
        val before = store.get(key)
        val records = decode(before)
        val next = transform(records).sortedWith(compareBy({ it.run.value }, { it.key.value }))
        validate(next)
        if (next != records) {
            val after = Json.encodeToString(next)
            journal.commit(
                after.encodeUtf8().sha256().hex(),
                mapOf(key.name to before?.let { HarnessRawValue(it) }),
                mapOf(key.name to HarnessRawValue(after)),
            )
        }
    }

    private fun decode(raw: String?): List<WorkflowOrganismGrant> {
        if (raw == null) return emptyList()
        return try {
            Json.decodeFromString<List<WorkflowOrganismGrant>>(raw).also(::validate)
        } catch (error: IllegalArgumentException) {
            log.w(
                HarnessStorageCorrupt(),
            ) { "Invalid workflow organism journal (${error::class.simpleName.orEmpty()})" }
            throw HarnessStorageCorrupt()
        }
    }

    private fun validate(records: List<WorkflowOrganismGrant>) {
        require(records.map { it.run to it.key }.distinct().size == records.size)
        require(records.map { it.organism }.distinct().size == records.size)
    }

    private suspend fun <T> access(block: suspend () -> T): T = lock.withLock {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: HarnessStorageException) {
            throw error
        } catch (error: Exception) {
            log.w(
                HarnessStorageUncertain(),
            ) { "Workflow organism storage unavailable (${error::class.simpleName.orEmpty()})" }
            throw HarnessStorageUncertain()
        }
    }
}
