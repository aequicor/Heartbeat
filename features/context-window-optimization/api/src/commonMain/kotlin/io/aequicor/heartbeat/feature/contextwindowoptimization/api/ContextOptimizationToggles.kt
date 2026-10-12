package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Context window optimization: manual compaction (`/compact`, the context indicator), the "Context" settings section
 * and the profile machine. While off, sessions keep only the compaction their engines run on their own.
 */
public val ContextOptimizationEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "context_optimization.enabled",
    "Оптимизация контекстного окна: сжатие, раздел настроек «Контекст»",
)

/** Compaction between turns once the context reaches the threshold of the settings; requires the main toggle. */
public val AutoCompactionEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "context_optimization.auto_compaction",
    "Автоматическое сжатие контекста между ходами по порогу",
)

/** Continuing a chat in a new native session seeded with the essence of the old one; requires the main toggle. */
public val ContextRolloverEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "context_optimization.rollover",
    "Перенос чата в новую сессию с выжимкой старой (rollover)",
)
