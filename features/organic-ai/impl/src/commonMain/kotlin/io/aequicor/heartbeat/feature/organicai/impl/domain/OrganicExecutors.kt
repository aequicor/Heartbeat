package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode

/** Canonical retirement and exact native cleanup, separate from logical completion of a cell. */
internal interface OrganicExecutors {
    suspend fun load(): List<Organism>
    suspend fun persist(organism: Organism)

    /** Stops cell requests and open judges for sleep without permanently retiring their identities. */
    suspend fun hibernate(organism: Organism): OrganicRelease
    suspend fun reconcileJudges(organism: Organism)
    suspend fun release(organism: Organism, cells: List<CellId>, mode: ReleaseMode): OrganicRelease
}

/** Confirmed members may release their harness resources; whole-organism release additionally covers judges. */
internal data class OrganicRelease(
    val stopped: Set<CellId>,
    val isConfirmed: Boolean,
    val references: Map<CellId, Set<SessionRef>> = emptyMap(),
)
