package io.aequicor.heartbeat.feature.agentlearning.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Agent self-learning: the hosted `remember` and `load_learned_skill` tools, learned instructions in the prompts of
 * new sessions, the `/remember` command, hints about frequent tool errors and the settings section. While off,
 * agents get neither the tools nor the stored instructions; the registry itself is kept.
 */
public val AgentLearningEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "agent_learning.enabled",
    "Самообучение агента: реестр выученных инструкций, инструмент remember и команда /remember",
)

/** Names of the hosted tools; transcripts match them to show learning cards. */
public object LearningTools {
    /** Saves a new instruction in the registry. */
    public const val REMEMBER: String = "remember"

    /** Returns the body of an enabled learned skill. */
    public const val LOAD_SKILL: String = "load_learned_skill"
}
