package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/** Legacy turn metadata retained for protocol compatibility; AgentSecuritySettings decides authorization. */
@Serializable
public enum class AgentToolDecisionMode {
    /** Legacy interactive label; no independent authorization authority. */
    Interactive,

    /** Legacy automatic label; the current shared profile mode determines confirmation. */
    AutomaticSafety,
}
