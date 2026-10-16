package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Lets approved harness scripts run their declared commands (mini-app buttons, timers) without an agent turn.
 * Declarations still compile and publish while it is off; runs are refused as Disabled.
 */
internal val HarnessCommandsEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "harness.commands",
    description = "Харнессы: команды мини-аппов без агента",
)
