package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/** Enables new subsession starts while the complete session model is being integrated. Recovery remains available. */
public val StudioSessionModel: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "ai_studio.session_model",
    description = "Единая модель сессий: полноценные субсессии и отдельные субагенты",
    default = false,
)

/** Hosted operations for full conversations; these never create owner-only subagent executors. */
public object SubsessionTools {
    /** Creates a full chat with the caller as its direct creator. */
    public const val SPAWN: String = "subsession_spawn"

    /** Sends a new message through the child's ordinary queue and current configuration. */
    public const val SEND: String = "subsession_send"

    /** Reads the currently observed attempt without sending anything. */
    public const val STATUS: String = "subsession_status"

    /** Reads an immutable result of an exact request. */
    public const val RESULT: String = "subsession_result"

    /** Explicit bounded polling wait, without starting or restarting the child. */
    public const val WAIT: String = "subsession_wait"

    /** Fences or stops one exact request; the full chat remains available. */
    public const val CANCEL: String = "subsession_cancel"
}
