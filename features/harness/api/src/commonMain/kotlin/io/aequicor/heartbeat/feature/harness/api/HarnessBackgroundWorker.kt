package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/** Explicit desktop worker is opt-in; it preserves all existing harness, scheduler and safety switches. */
public val HarnessBackgroundWorker: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "harness.background_worker",
    description = "Харнессы: отдельный фоновый исполнитель без окна студии",
    default = false,
)
