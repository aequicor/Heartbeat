package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/** Enables versioned process execution and protected archival; already versioned records retain their safeguards. */
internal val HarnessDurableProcessesEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "harness.durable_processes",
    description = "Харнессы: версии длительных процессов и защищённый архив",
    default = false,
)
