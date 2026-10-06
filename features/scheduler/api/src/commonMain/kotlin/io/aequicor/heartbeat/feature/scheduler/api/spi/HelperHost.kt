package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId

/** Empty helper creation. Persist identity, owner, parent, helper marker and trust cap before returning. */
public data class HelperCreateRequest(
    val owner: ActionId,
    val parent: SessionRef?,
    val workspace: WorkspaceRef?,
    val target: EngineTarget,
    val title: String,
    val trustCap: TrustLevel,
) {
    override fun toString(): String = "HelperCreateRequest(owner=$owner, hasParent=${parent != null})"
}

/**
 * Durable metadata of a marked helper. An ordinary chat must return null instead. [lastRequest] includes uncertain
 * or in-flight submissions; a recovering lease must reconcile it before sending another request or releasing.
 */
public data class HelperMetadata(
    val id: HelperId,
    val owner: ActionId,
    val parent: SessionRef?,
    val session: SessionRef?,
    val lastRequest: RequestId?,
) {
    override fun toString(): String = "HelperMetadata(id=$id, owner=$owner, hasRequest=${lastRequest != null})"
}
