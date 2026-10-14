package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/** Trusted host choice for a turn; model arguments never select or change how permission is decided. */
@Serializable
public enum class AgentToolDecisionMode {
    /** Ordinary trust rules may request a user decision. */
    Interactive,

    /** Independent safety review allows an exact operation or refuses it; never requests a user decision. */
    AutomaticSafety,
}
