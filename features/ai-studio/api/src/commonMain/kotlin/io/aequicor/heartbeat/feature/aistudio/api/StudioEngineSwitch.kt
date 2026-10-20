package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget

/** Explicit replacement of a chat's native context; the expected route protects delayed menu actions. */
public data class StudioEngineSwitch(val expected: EngineTarget, val target: EngineTarget)

/** Profile-owned replacement status. A failure never authorizes replay of a native request. */
public data class StudioEngineSwitchState(
    val isPending: Boolean = false,
    val failure: StudioEngineSwitchFailure? = null,
)

/** Reasons the chat could not change its native route. */
public enum class StudioEngineSwitchFailure {
    /** The feature is disabled. */
    Disabled,

    /** The chat or its request receipts are busy or uncertain. */
    Busy,

    /** The route changed since the menu action was prepared. */
    Stale,

    /** The selected route cannot run this chat. */
    Unsupported,

    /** Persistence or native inspection failed; retry may first recover the journal. */
    Unavailable,
}

/** User-controlled engine and connection switching; independent of context-window optimization. */
public val StudioEngineSwitching: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "ai_studio.engine_switching",
    description = "Переключение движка рабочего чата с сохранением контекста",
    default = false,
)
