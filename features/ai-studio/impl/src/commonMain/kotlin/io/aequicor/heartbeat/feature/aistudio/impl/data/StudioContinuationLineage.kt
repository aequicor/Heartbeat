package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.CancellationException

/** Resolve one immutable chain in this conversation; map hints never establish ancestry. */
internal fun continuationChain(
    records: Map<String, StudioHelperReceipt>,
    request: RequestId,
): List<StudioHelperReceipt> {
    var root = records[request.value] ?: return emptyList()
    val ancestors = mutableSetOf<RequestId>()
    while (true) {
        val source = root.continuationOf ?: break
        check(ancestors.add(root.request)) { "Cyclic continuation ancestry" }
        val parent = checkNotNull(records[source.value]) { "Continuation source is missing" }
        requireContinuationEdge(records, parent, root)
        root = parent
    }
    val chain = mutableListOf(root)
    val visited = mutableSetOf(root.request)
    while (true) {
        val children = records.values.filter { it.continuationOf == chain.last().request }
        check(children.size <= 1) { "Continuation has multiple successors" }
        val next = children.singleOrNull() ?: break
        check(visited.add(next.request)) { "Cyclic continuation ownership" }
        requireContinuationEdge(records, chain.last(), next)
        chain += next
    }
    check(chain.all { records[it.request.value] == it }) { "Continuation receipt key changed" }
    return chain
}

/** Native identity and sender claims belong only to their own node, never to an inherited policy snapshot. */
private fun requireContinuationEdge(
    records: Map<String, StudioHelperReceipt>,
    source: StudioHelperReceipt,
    next: StudioHelperReceipt,
) {
    check(source.request != next.request && next.continuationOf == source.request) { "Invalid continuation edge" }
    if (next.graphContinuation == null) {
        requireContinuationSource(source)
    } else {
        next.graphContinuation.requireSource(records, source, next)
    }
    check(next.policy == source.policy && next.handoff == source.handoff && next.causes == source.causes) {
        "Continuation changed its work ownership or provenance"
    }
    check(next.executionSnapshot == source.executionSnapshot) { "Continuation changed its execution snapshot" }
}

private fun requireContinuationSource(source: StudioHelperReceipt) {
    check(source.policy != null && source.policy != StudioAttemptPolicy.UnknownService) {
        "Unknown execution cannot grant continuation authority"
    }
    check(
        source.phase == StudioHelperPhase.Terminal && source.terminal?.outcome != StudioHelperTerminalOutcome.Unknown,
    ) {
        "Continuation source needs exact native terminal evidence"
    }
    val snapshot = checkNotNull(source.executionSnapshot) { "Legacy execution requires snapshot reconciliation" }
    check(source.causes == snapshot.causes) { "Continuation causal provenance is missing or contradictory" }
    val policy = source.policy
    check(policy !is StudioAttemptPolicy.OwnedHelperWork || policy.binding.reservation != null) {
        "A helper continuation needs its exact work reservation"
    }
}

/** Caller holds route and journal locks and has verified the exact worktree producer claim. */
internal fun prepareContinuationReceipt(
    records: Map<String, StudioHelperReceipt>,
    source: RequestId,
    prompt: HelperPrompt,
): StudioHelperPreparation {
    require(source != prompt.request) { "A continuation needs a new request id" }
    val chain = continuationChain(records, source)
    val previous = checkNotNull(chain.firstOrNull { it.request == source }) { "Continuation source is missing" }
    requireContinuationSource(previous)
    if (chain.any { it.isContinuationFenced }) {
        throw CancellationException(
            "Continuation lineage was stopped",
        )
    }
    val existing = records[prompt.request.value]
    val receipt = StudioHelperReceipt(
        prompt.request,
        StudioHelperPhase.Preparing,
        prompt.fingerprint(),
        handoff = previous.handoff,
        isNativeEntryTracked = true,
        causes = previous.causes,
        policy = previous.policy,
        executionSnapshot = previous.executionSnapshot,
        continuationOf = source,
    )
    if (existing != null) {
        check(existing.graphContinuation == null) { "A virtual action cannot replace a graph native edge" }
        requireContinuationEdge(records, previous, existing)
        check(existing.fingerprint == receipt.fingerprint) { "Continuation payload changed" }
        val isRetry = existing.phase == StudioHelperPhase.NotSubmitted && existing.isNativeEntryTracked
        if (isRetry) check(records.unresolvedHeads().isEmpty()) { "Another native attempt is unresolved" }
        return if (isRetry) StudioHelperPreparation(receipt, isNew = true) else StudioHelperPreparation(existing, false)
    }
    check(chain.last().request == source) { "Continuation source already has a successor" }
    check(records.unresolvedHeads().isEmpty()) { "Another native attempt is unresolved" }
    return StudioHelperPreparation(receipt, isNew = true)
}

/** The expected action is retained after delivery; the machine checks it again before native preparation. */
internal fun requireContinuationClaim(task: WorktreeTask, request: StudioTurnRequest) {
    val source = checkNotNull(request.continuationOf)
    val expected = checkNotNull(task.expectedAction) { "Continuation producer operation is missing" }
    check(task.chatId == request.id && expected.operation == request.request.value) {
        "Continuation has no exact producer operation"
    }
    check(
        expected.kind == request.kind && expected.continuationOf == source && request.kind == WorktreeRunKind.Coding,
    ) {
        "Continuation producer intent changed"
    }
    val run = checkNotNull(task.run) { "Continuation native source is missing" }
    check(run.request == source && source in task.virtualInterruptedRequests) {
        "Continuation does not own the interrupted source"
    }
    check(run.outcome != null && run.outcome != TurnOutcome.Unknown && task.actionRequest == null) {
        "Continuation producer has not confirmed its source"
    }
}

/** Stop is durable on every ancestor before returning the effective native head. */
internal fun stopContinuationChain(
    records: Map<String, StudioHelperReceipt>,
    request: RequestId,
    shouldStopNative: Boolean,
): Pair<Map<String, StudioHelperReceipt>, StudioHelperReceipt> {
    val chain = continuationChain(records, request)
    val receipt = chain.lastOrNull()
    val stopped = when {
        receipt == null -> StudioHelperReceipt(request, StudioHelperPhase.NotSubmitted, null, isFinal = true)

        receipt.phase in setOf(StudioHelperPhase.Preparing, StudioHelperPhase.NotSubmitted) ||
            (
                receipt.phase == StudioHelperPhase.Submitting && receipt.isNativeEntryTracked &&
                    receipt.nativeEntryClaim == null
            ) ->
            receipt.copy(phase = StudioHelperPhase.NotSubmitted, isFinal = true, isStopRequested = true)

        else -> receipt.copy(
            isStopRequested = receipt.isStopRequested || shouldStopNative,
            isContinuationRevoked = true,
        )
    }
    val fenced = records + chain.associate {
        it.request.value to it.copy(
            isStopRequested = it.isStopRequested || shouldStopNative,
            isContinuationRevoked = true,
        )
    }
    return (fenced + (stopped.request.value to stopped)) to stopped
}

internal val StudioHelperReceipt.isContinuationFenced: Boolean
    get() = isStopRequested || isContinuationRevoked || isFinal

/** Final revocation of an unsent child cannot retroactively make its native ancestor NotSubmitted. */
internal data class StudioContinuationResolution(val head: StudioHelperReceipt, val result: StudioHelperReceipt)

internal fun resolveContinuation(
    records: Map<String, StudioHelperReceipt>,
    request: RequestId,
): StudioContinuationResolution? {
    val chain = continuationChain(records, request)
    val head = chain.lastOrNull() ?: return null
    val result = if (head.request != request && head.phase == StudioHelperPhase.NotSubmitted && head.isFinal) {
        chain.dropLast(1).lastOrNull { it.terminal != null || it.isUnresolved } ?: head
    } else {
        head
    }
    return StudioContinuationResolution(head, result)
}
