package io.aequicor.heartbeat.feature.plantumlsupport.impl.domain

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/** Draws PlantUML fences of chat and coding-session Markdown as images on desktop; off until the feature is ready. */
internal val PlantUmlEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "plantuml.enabled",
    "Диаграммы PlantUML в markdown чатов и кодинг-сессий: локальный рендер на Desktop",
)
