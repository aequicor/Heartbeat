package io.aequicor.heartbeat.feature.aiengine.facade.api

/** Host-owned analysis of native execution. An adapter's action category is not a verified contract. */
public fun interface NativeActionAnalysis {
    /** Supplies optional structural facts; unknown facts leave the full invocation to the shared policy review. */
    public suspend fun analyze(context: AgentToolContext, call: NativeToolCall): ActionAnalysis
}
