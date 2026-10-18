package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Context window optimization: manual compaction (`/compact`, the context indicator), the "Context" settings section
 * and the profile machine. While off, sessions keep only the compaction their engines run on their own.
 */
public val ContextOptimizationEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "context_optimization.enabled",
    "Оптимизация контекстного окна: сжатие, раздел настроек «Контекст»",
    default = true,
)

/** Compaction between turns once the context reaches the threshold of the settings; requires the main toggle. */
public val AutoCompactionEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "context_optimization.auto_compaction",
    "Автоматическое сжатие контекста между ходами по порогу",
    default = true,
)

/** Continuing a chat in a new native session seeded with the essence of the old one; requires the main toggle. */
public val ContextRolloverEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "context_optimization.rollover",
    "Перенос чата в новую сессию с выжимкой старой (rollover)",
    default = true,
)

/** The progress ledger and the agent's `context_*` tools; requires the main toggle. */
public val ContextLedgerEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "context_optimization.ledger_tools",
    "Журнал прогресса сессии и инструменты агента context_*",
    default = true,
)

/** Observation masking of older tool results in prompts that engines rebuild every turn (Koog). */
public val KoogMaskingEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "context_optimization.koog_masking",
    "Маскирование старых результатов инструментов в промпте Koog",
    default = true,
)

/** Long tool output kept out of the context, readable with `context_recall`; requires the main toggle. */
public val ContextOffloadEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "context_optimization.offload",
    "Выгрузка длинных выводов инструментов из контекста и инструмент context_recall",
    default = true,
)

/**
 * A continuation brief after compaction and rollover — every user message, a summary of the work and the last turn —
 * so the agent continues where it stopped; requires the main toggle.
 */
public val ContinuationBriefEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "context_optimization.continuation_brief",
    "Бриф продолжения после сжатия: сообщения пользователя, выжимка работы и последний ход",
    default = true,
)

/** Names of the agent's hosted context tools. */
public object ContextOptimizationTools {
    /** Reports context occupancy, the ledger and pending compaction. */
    public const val STATUS: String = "context_status"

    /** Records progress in the ledger. */
    public const val CHECKPOINT: String = "context_checkpoint"

    /** Asks to compact the context after the current turn. */
    public const val COMPACT: String = "context_compact"

    /** Reads a part of a long tool output that was kept out of the context. */
    public const val RECALL: String = "context_recall"
}
