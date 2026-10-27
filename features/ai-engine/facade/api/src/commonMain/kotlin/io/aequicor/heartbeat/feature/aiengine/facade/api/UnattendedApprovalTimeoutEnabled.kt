package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * A confirmation request is declined once the Heartbeat window has been out of focus for 30 s, unless an agent
 * is driving the computer. Answers from the Chromium side panel do not count as attention yet.
 */
public val UnattendedApprovalTimeoutEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "ai.unattended_approval_timeout",
    "Автоотказ запроса подтверждения через 30 с, если окно не в фокусе",
    default = false,
)
