package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
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
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowHelperGrant
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowHelperJournal
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoveryRecord
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoverySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock

/** Small permanent aggregate bounded by shared capacity, with a durable commit marker and strict decoding. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class StoredWorkflowHelperJournal(private val store: KeyValueStore, clock: Clock) : WorkflowHelperJournal {
    @Inject
    constructor(
        @ForScope(ProfileScope::class) stores: DataStores,
        clock: Clock,
    ) : this(stores.keyValue(KeyValueSpec("harness_workflow_helpers")), clock)

    private val lock = Mutex()
    private val journal = HarnessKeyValueJournal(store, clock)
    private val log = Log.tag("HarnessWorkflow")
    private val key = stringKey("grants")

    override suspend fun granted(grant: WorkflowHelperGrant) = update { records ->
        require(grant.helper == null) { "Initial capacity proof cannot bind a helper" }
        val old = records.singleOrNull { it.reservation == grant.reservation }
        when {
            old != null && old.copy(helper = null) == grant -> records
            old != null || records.any { it.run == grant.run && it.key == grant.key } -> throw HarnessStorageConflict()
            else -> records + grant
        }
    }

    override suspend fun bind(reservation: ActionId, helper: HelperId): WorkflowHelperGrant {
        var bound: WorkflowHelperGrant? = null
        update { records ->
            val old = records.singleOrNull { it.reservation == reservation }
            val isReused = records.any { it.reservation != reservation && it.helper == helper }
            val isRebound = old?.helper?.let { it != helper } ?: false
            if (old == null || isRebound || isReused) throw HarnessStorageConflict()
            val next = old.copy(helper = helper)
            bound = next
            records.map { if (it.reservation == reservation) next else it }
        }
        return checkNotNull(bound)
    }

    override suspend fun pending(): List<WorkflowHelperGrant> = access {
        journal.recover()
        decode(store.get(key))
    }

    override suspend fun settled(grant: WorkflowHelperGrant) = update { records ->
        val old = records.singleOrNull { it.reservation == grant.reservation }
        if (old != null && old != grant) throw HarnessStorageConflict()
        records.filterNot { it.reservation == grant.reservation }
    }

    private suspend fun update(transform: (List<WorkflowHelperGrant>) -> List<WorkflowHelperGrant>) = access {
        journal.recover()
        val before = store.get(key)
        val records = decode(before)
        val next = transform(records).sortedBy { it.reservation.value }
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

    private fun decode(raw: String?): List<WorkflowHelperGrant> {
        if (raw == null) return emptyList()
        return try {
            Json.decodeFromString<List<WorkflowHelperGrant>>(raw).also(::validate)
        } catch (error: IllegalArgumentException) {
            log.w(
                HarnessStorageCorrupt(),
            ) { "Invalid workflow capacity journal (${error::class.simpleName.orEmpty()})" }
            throw HarnessStorageCorrupt()
        }
    }

    private fun validate(records: List<WorkflowHelperGrant>) {
        require(records.map { it.reservation }.distinct().size == records.size)
        require(records.map { it.run to it.key }.distinct().size == records.size)
        val helpers = records.mapNotNull { it.helper }
        require(helpers.distinct().size == helpers.size)
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
            ) { "Workflow capacity storage unavailable (${error::class.simpleName.orEmpty()})" }
            throw HarnessStorageUncertain()
        }
    }
}

/** Recovery participates even while the harness feature is disabled; it never launches a helper. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class WorkflowHelperCapacityRecovery(private val journal: Lazy<WorkflowHelperJournal>) :
    HelperCapacityRecoverySource {
    override suspend fun reservations(): List<HelperCapacityRecoveryRecord> = journal.value.pending().map {
        HelperCapacityRecoveryRecord(it.reservation, ActionId(it.run.value), it.parent, it.helper)
    }
}
