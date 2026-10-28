package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId

/**
 * Health of every bundle the production graph started, the worse of its app level and of its profile graph as the last
 * completed `openProfile` found it. Exists for the headless smoke of the processed train (`verifyShrunkTrain`), which
 * reads it from outside this module; `train-rules.pro` keeps it for that.
 */
fun HeartbeatGraph.bundleHealth(): Map<BundleId, BundleHealth> {
    val host = (this as JvmHeartbeatGraph).bundleHost
    val profile = host.lastProfileHealth
    return host.health.mapValues { (id, app) -> worseHealth(app, profile[id] ?: app) }
}

/** Whether a profile was opened at all: an empty profile health must not read as "every profile graph is healthy". */
fun HeartbeatGraph.hasProfileHealth(): Boolean = (this as JvmHeartbeatGraph).bundleHost.lastProfileHealth.isNotEmpty()

/** The worse of two states: failed, then degraded, then healthy. */
internal fun worseHealth(app: BundleHealth, profile: BundleHealth): BundleHealth = when {
    app is BundleHealth.Failed -> app
    profile is BundleHealth.Failed -> profile
    app is BundleHealth.Degraded -> app
    else -> profile
}
