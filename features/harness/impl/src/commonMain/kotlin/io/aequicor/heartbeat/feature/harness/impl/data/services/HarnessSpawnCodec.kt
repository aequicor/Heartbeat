package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageCorrupt
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnOwner
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnRecord
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned identity codec; corrupt private metadata never appears in exceptions or logs. */
internal object HarnessSpawnCodec {
    private val log = Log.tag("HarnessSpawnJournal")

    fun encode(record: HarnessSpawnRecord): HarnessSpawnEntity = HarnessSpawnEntity(
        record.reservation.value,
        Json.encodeToString(
            StoredHarnessSpawn(
                1,
                record.action,
                record.owner.harness,
                record.owner.item,
                record.owner.generation,
                record.parent,
                record.request,
                record.attachRequest,
                HarnessAncestryCodec.encode(record.origin),
            ),
        ),
        record.helper?.value,
    )

    fun decode(record: HarnessSpawnEntity): HarnessSpawnRecord = try {
        val stored = Json.decodeFromString<StoredHarnessSpawn>(record.identity)
        require(stored.version == 1) { "Unsupported spawn journal version" }
        HarnessSpawnRecord(
            ActionId(record.reservation),
            stored.action,
            HarnessSpawnOwner(stored.harness, stored.item, stored.generation),
            stored.parent,
            stored.request,
            stored.attachRequest,
            HarnessAncestryCodec.decode(stored.origin),
            record.helper?.let(::HelperId),
        )
    } catch (error: IllegalArgumentException) {
        val safe = HarnessStorageCorrupt("SpawnJournal")
        log.w(safe) { "Spawn journal decode failed (${error::class.simpleName.orEmpty()})" }
        throw safe
    }
}

@Serializable
private data class StoredHarnessSpawn(
    val version: Int,
    val action: ActionId,
    val harness: HarnessId,
    val item: ItemId,
    val generation: Long,
    val parent: SessionRef?,
    val request: RequestId,
    val attachRequest: RequestId,
    val origin: String,
) {
    override fun toString(): String = "StoredHarnessSpawn(***)"
}
