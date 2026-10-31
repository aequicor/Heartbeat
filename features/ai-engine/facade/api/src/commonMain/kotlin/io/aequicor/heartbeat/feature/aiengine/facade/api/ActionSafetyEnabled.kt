package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/** Structured action evidence and two-stage automatic review across hosted and native execution. */
public val ActionSafetyEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "ai.action_safety",
    "Структурная проверка действий (после перезапуска профиля)",
    default = true,
)
