package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

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

/** Bounds for durable helper enumeration; historical helpers do not consume live capacity. */
public object HelperMetadataLimits {
    /** Default number of metadata records returned per owner page. */
    public const val DEFAULT_PAGE_SIZE: Int = 64

    /** Maximum number of records in one owner page. */
    public const val MAX_PAGE_SIZE: Int = 128
}
