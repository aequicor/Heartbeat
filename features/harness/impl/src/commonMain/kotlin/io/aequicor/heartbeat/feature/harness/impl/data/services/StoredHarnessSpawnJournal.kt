package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnJournal
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnRecord
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoveryRecord
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCapacityRecoverySource

/** Durable pending acquisitions independent of the library machine, script availability and feature toggle. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredHarnessSpawnJournal(
    @ForScope(ProfileScope::class) stores: DataStores,
) : HarnessSpawnJournal {
    private val dao by lazy { stores.database(HarnessAncestryDatabaseSpec).spawns() }
    private val log = Log.tag("HarnessSpawnJournal")

    override suspend fun recordGranted(record: HarnessSpawnRecord) {
        log.v { "Persist granted helper capacity before creation" }
        dao.recordGranted(HarnessSpawnCodec.encode(record))
    }

    override suspend fun bindHelper(reservation: ActionId, helper: HelperId): HarnessSpawnRecord {
        log.v { "Bind helper before first prompt" }
        return HarnessSpawnCodec.decode(dao.bindHelper(reservation.value, helper.value))
    }

    override suspend fun pending(): List<HarnessSpawnRecord> = dao.pending().map(HarnessSpawnCodec::decode)

    override suspend fun settle(record: HarnessSpawnRecord) {
        log.v { "Remove confirmed helper cleanup proof" }
        dao.settle(HarnessSpawnCodec.encode(record))
    }
}

/** Initial shared-capacity restore reads durable evidence only; no host/runtime calls or replayed submissions. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessSpawnCapacityRecovery(private val journal: Lazy<HarnessSpawnJournal>) :
    HelperCapacityRecoverySource {
    override suspend fun reservations(): List<HelperCapacityRecoveryRecord> = journal.value.pending().map {
        HelperCapacityRecoveryRecord(it.reservation, it.action, it.parent, it.helper)
    }
}
