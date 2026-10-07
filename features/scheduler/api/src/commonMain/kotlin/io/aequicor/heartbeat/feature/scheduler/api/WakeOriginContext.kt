package io.aequicor.heartbeat.feature.scheduler.api

/** Projects only host-owned causal metadata, without exposing the wake note or routing details. */
public fun WakeRequest.ownedOrigin(): EventOrigin.Feature? {
    val context = ownerContext
    val feature = ownerFeature
    return if (context != null && feature != null) EventOrigin.Feature(feature, context) else null
}
