package io.aequicor.heartbeat.feature.adaptivesubagents.api

/** Hosted tools of subagents in ordinary chats and their arguments. */
public object SubagentTools {
    /** Starts a subagent on an adaptively chosen model configuration. */
    public const val SPAWN: String = "subagent_spawn"

    /** Reads finished subagents of this session, or sleeps until one finishes. */
    public const val RECEIVE: String = "subagent_receive"

    /** The scheduler owner retaining subagent results until this session reads them. */
    public const val OWNER: String = "adaptive_subagents"

    /** Argument names. */
    public object Arguments {
        /** The self-contained task. */
        public const val TASK: String = "task"

        /** The subagent chat's title. */
        public const val TITLE: String = "title"

        /** quality, balanced, speed or cost. */
        public const val PRIORITY: String = "priority"

        /** low, medium or high. */
        public const val COMPLEXITY: String = "complexity"

        /** Required inputs: images, pdf, long_context. */
        public const val NEEDS: String = "needs"

        /** Task kinds, see [ModelTags]. */
        public const val TAGS: String = "tags"

        /** Sleep until a subagent finishes when none has yet. */
        public const val WAIT: String = "wait"
    }
}
