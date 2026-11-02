package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskPhase
import io.aequicor.heartbeat.feature.scheduler.api.GraphTaskResult

/** Graph metadata groups one assignment; only the native journal may identify its effective execution. */
internal fun Map<String, GraphChatAttempt>.graphWorkRoot(chat: String, execution: String): String {
    var current = execution
    val visited = mutableSetOf<String>()
    while (true) {
        check(visited.add(current)) { "Cyclic graph work roots" }
        val attempt = this[current] ?: return current
        check(attempt.chat == chat) { "Graph work changed conversations" }
        val root = attempt.recoveryRoot ?: return current
        if (root == current) return current
        current = root
    }
}

internal fun Map<String, GraphChatAttempt>.recoveryLineage(
    chatId: String,
    root: String,
): Map<String, GraphChatAttempt> {
    val actual = graphWorkRoot(chatId, root)
    return filter { (execution, attempt) -> attempt.chat == chatId && graphWorkRoot(chatId, execution) == actual }
}

/** Metadata recovery deliberately returns no cached outcome or checkpoint without matching native-head proof. */
internal fun Map<String, GraphChatAttempt>.recoveryAttempt(
    chatId: String,
    previousExecution: String?,
): GraphChatAttempt? {
    if (previousExecution == null) return null
    val root = graphWorkRoot(chatId, previousExecution)
    val lineage = recoveryLineage(chatId, root)
    if (lineage.isEmpty()) return null
    val original = lineage[root]
    val work = original?.work ?: lineage.values.mapNotNull { it.work }.distinct().singleOrNull()
    check(work != null || lineage.values.all { it.work == null }) { "Graph assignment snapshots conflict" }
    lineage.values.forEach { retainGraphWork(work, it.work) }
    val causes = original?.causes ?: lineage.values.map { it.causes }.distinct().single()
    return GraphChatAttempt(chatId, recoveryRoot = root, causes = causes, work = work)
}

/** A stale result for R cannot satisfy an assignment whose actual native head is Q, regardless of map order. */
internal fun Map<String, GraphChatAttempt>.cachedGraphResult(
    chatId: String,
    root: String,
    work: StudioRecoveryWorkResolution,
): GraphTaskResult? {
    if (!work.isConsistent) return null
    val matching = recoveryLineage(chatId, root).filterValues { it.matchesNativeHead(work) }
    check(matching.values.mapNotNull { it.result?.phase }.distinct().size <= 1) {
        "Graph results contradict one native outcome"
    }
    return matching.minByOrNull { it.key }?.value?.result
        ?: work.receipt?.takeIf { it.request == work.nativeRequest }?.terminal?.let { terminal ->
            GraphTaskResult(terminal.outcome.graphPhase(), terminal.answer)
        }
}

private fun GraphChatAttempt.matchesNativeHead(work: StudioRecoveryWorkResolution): Boolean {
    val identity = native ?: return false
    val completed = result?.takeUnless { it.phase == GraphTaskPhase.RecoveryRequired } ?: return false
    if (identity.request != work.nativeRequest) return false
    return if (work.receipt != null) {
        work.receipt.matchesGraphResult(identity, completed.phase)
    } else {
        work.observer?.matchesGraphResult(identity, completed.phase) == true
    }
}

private fun StudioHelperReceipt.matchesGraphResult(identity: GraphNativeIdentity, result: GraphTaskPhase): Boolean {
    val isAccepted = phase in setOf(StudioHelperPhase.Accepted, StudioHelperPhase.Terminal) ||
        (phase == StudioHelperPhase.Submitting && (!isNativeEntryTracked || nativeEntryClaim != null))
    val isIdentityMatching = (session ?: preparedSession) == identity.session && identity.turn.matchesKnown(turn)
    return isAccepted && isIdentityMatching && terminal?.outcome.matchesGraphPhase(result)
}

private fun StudioRecoveryWitness.matchesGraphResult(identity: GraphNativeIdentity, result: GraphTaskPhase): Boolean {
    val isIdentityMatching = observed == identity.request && session == identity.session && identity.turn.matchesKnown(
        turn,
    )
    return isIdentityMatching && outcome.matchesGraphPhase(result)
}

private fun io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId?.matchesKnown(
    known: io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId?,
): Boolean = this == null || known == null || this == known

private fun StudioHelperTerminalOutcome?.matchesGraphPhase(phase: GraphTaskPhase): Boolean =
    this == null || this == StudioHelperTerminalOutcome.Unknown || graphPhase() == phase

private fun StudioHelperTerminalOutcome.graphPhase(): GraphTaskPhase = when (this) {
    StudioHelperTerminalOutcome.Completed -> GraphTaskPhase.Succeeded
    StudioHelperTerminalOutcome.Failed -> GraphTaskPhase.Failed
    StudioHelperTerminalOutcome.Cancelled -> GraphTaskPhase.Cancelled
    StudioHelperTerminalOutcome.Unknown -> GraphTaskPhase.RecoveryRequired
}
