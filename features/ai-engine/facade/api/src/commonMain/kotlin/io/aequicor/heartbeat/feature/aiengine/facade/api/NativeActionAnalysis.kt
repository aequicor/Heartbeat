package io.aequicor.heartbeat.feature.aiengine.facade.api

/** Host-owned analysis of native execution. An adapter's action category is not a verified contract. */
public fun interface NativeActionAnalysis {
    /** Resolves complete execution facts, or fails closed when the executor or resources cannot be established. */
    public suspend fun analyze(context: AgentToolContext, call: NativeToolCall): ActionAnalysis
}
