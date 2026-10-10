package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId

/**
 * Empty helper creation. Persist identity, owner, parent, helper marker and trust cap before returning.
 * A null [target] inherits the durable target of [parent], failing if it is unknown. Only a parentless helper
 * may leave its target unset for the host to select its current default on the first prompt.
 */
public data class HelperCreateRequest(
    val owner: ActionId,
    val parent: SessionRef?,
    val workspace: WorkspaceRef?,
    val target: EngineTarget?,
    val title: String,
    val trustCap: TrustLevel,
) {
    override fun toString(): String = "HelperCreateRequest(owner=$owner, hasParent=${parent != null})"
}

/** Compatibility name for the public read-only helper metadata contract. */
public typealias HelperMetadata = io.aequicor.heartbeat.feature.scheduler.api.HelperMetadata
