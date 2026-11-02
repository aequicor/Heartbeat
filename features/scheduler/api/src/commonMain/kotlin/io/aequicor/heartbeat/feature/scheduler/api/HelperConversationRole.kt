package io.aequicor.heartbeat.feature.scheduler.api

/** Host-selected interaction contract, separate from the lease's work owner and native parent. */
public enum class HelperConversationRole {
    /** Compatibility contract of ScriptSessions.spawn: direct human input and configuration remain available. */
    FullConversation,

    /** Work executor; only a verified full conversation can become its logical owner. */
    Executor,
}
