package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId

/**
 * Health of every bundle the production graph started. The headless smoke of the processed train
 * (`verifyShrunkTrain`) reads it from outside this module; `train-rules.pro` keeps it for that.
 */
fun HeartbeatGraph.bundleHealth(): Map<BundleId, BundleHealth> = (this as JvmHeartbeatGraph).bundleHost.health
