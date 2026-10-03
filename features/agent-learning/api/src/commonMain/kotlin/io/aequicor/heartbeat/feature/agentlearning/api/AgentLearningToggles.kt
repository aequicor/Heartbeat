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

    /** Argument names of [REMEMBER] and [LOAD_SKILL]; a kind is the serial name of [InstructionKind]. */
    public object Arguments {
        /** Kind of the new instruction. */
        public const val KIND: String = "kind"

        /** Title of the instruction; a skill's unique name. */
        public const val TITLE: String = "title"

        /** Body of the instruction. */
        public const val CONTENT: String = "content"

        /** When a skill applies. */
        public const val DESCRIPTION: String = "description"

        /** For a model instruction: `engine` or `model`. */
        public const val MODEL_SCOPE: String = "model_scope"

        /** The agent's rating: `safe` or `review`. */
        public const val SAFETY: String = "safety"

        /** What led to the lesson. */
        public const val REASON: String = "reason"

        /** Skill name of [LOAD_SKILL]. */
        public const val NAME: String = "name"
    }
}
