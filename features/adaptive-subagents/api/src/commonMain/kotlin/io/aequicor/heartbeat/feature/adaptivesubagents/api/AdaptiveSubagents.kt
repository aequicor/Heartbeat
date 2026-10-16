package io.aequicor.heartbeat.feature.adaptivesubagents.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Adaptive subagents: the library of model descriptions, its settings section and automatic selection of a
 * subagent's model configuration by cost, speed and quality. Off: organisms and harnesses keep inheriting models.
 */
public val AdaptiveSubagentsEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "adaptive_subagents.enabled",
    "Адаптивные субагенты: описания моделей и подбор конфигурации",
)

/** Automatic model descriptions written from web search, page reading and models.dev facts. */
public val AdaptiveSubagentsResearch: FeatureToggle.Flag = FeatureToggle.Flag(
    "adaptive_subagents.research",
    "Автоописание моделей ресерчем",
)

/** The LLM router choosing among the shortlist by the task text; off — scoring alone decides. */
public val AdaptiveSubagentsRouter: FeatureToggle.Flag = FeatureToggle.Flag(
    "adaptive_subagents.router",
    "LLM-роутер подбора модели субагента",
)

/** Tools `subagent_*` in ordinary chats; they also need scheduler actions. */
public val AdaptiveSubagentsSessionTools: FeatureToggle.Flag = FeatureToggle.Flag(
    "adaptive_subagents.session_tools",
    "Субагенты в обычных чатах",
)

/**
 * The settings section of adaptive subagents: preferences, connection billing and the model library.
 * [isEmbedded] marks the route drawn as a section of the settings window, where only the content is shown.
 */
@Serializable
@SerialName("adaptive-subagents")
public data class AdaptiveSubagentsRoute(public val isEmbedded: Boolean = false) : Route

/** The description editor of the model [model] of engine [engine] (raw ids of [ModelKey]). */
@Serializable
@SerialName("adaptive-model-profile")
public data class ModelProfileRoute(public val engine: String, public val model: String) : Route
