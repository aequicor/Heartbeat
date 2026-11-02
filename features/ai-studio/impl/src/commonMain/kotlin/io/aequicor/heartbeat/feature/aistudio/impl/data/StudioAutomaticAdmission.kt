package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId

/** Called under the journal mutex so a source Stop and automatic native entry have one ordering. */
internal suspend fun KeyValueStore.requireAutomaticSource(
    helper: HelperId,
    receipts: Map<String, StudioHelperReceipt>,
    source: StudioConversationSource,
    request: RequestId,
    isPrepared: Boolean,
) {
    val record = resolvedStudioIdentities(get(ChatsKey).orEmpty()).singleOrNull { it.id == helper.value }
    check(record?.isCompactable == true && source.chat == helper.value) { "Automatic conversation owner changed" }
    val expectedRequest = if (isPrepared) request else source.request
    val expectedRevision = source.revision + if (isPrepared) 1 else 0
    check(record.lastRunRequest == expectedRequest && record.runRevision == expectedRevision) {
        "Automatic conversation source was replaced"
    }
    check(source.actionFences.areUnfenced(receipts)) { "Automatic host action was stopped" }
    val visited = mutableSetOf(request)
    var current: StudioConversationSource? = source
    while (current != null) {
        check(current.chat == helper.value && visited.add(current.request)) { "Invalid automatic source chain" }
        val receipt = checkNotNull(receipts[current.request.value]) { "Automatic source evidence is missing" }
        check(receipt.isAutomaticConversationSource && receipt.causes.orEmpty() == current.causes) {
            "Automatic conversation source is fenced or unproven"
        }
        current = receipt.automaticSource
    }
}

/** Virtual continuations retain every automatic ancestor fence without reusing that ancestor's record revision. */
internal fun requireAutomaticAncestors(receipts: Map<String, StudioHelperReceipt>, request: RequestId) {
    check(automaticAncestorsValid(receipts, request)) { "Automatic ancestor was stopped or lost its provenance" }
}

private fun automaticAncestorsValid(receipts: Map<String, StudioHelperReceipt>, request: RequestId): Boolean {
    if (request.value !in receipts) return true // A fresh independent request has no ancestry yet.
    val pending = mutableListOf(request to false)
    val active = mutableSetOf<RequestId>()
    val completed = mutableSetOf<RequestId>()
    var isValid = true
    while (pending.isNotEmpty() && isValid) {
        val (current, isExit) = pending.removeAt(pending.lastIndex)
        if (isExit) {
            active.remove(current)
            completed.add(current)
        } else if (current !in completed) {
            val parents = receipts[current.value]?.automaticParents(receipts)
            isValid = active.add(current) && parents != null
            if (isValid) {
                pending.add(current to true)
                parents.orEmpty().forEach { pending.add(it to false) }
            }
        }
    }
    return isValid
}

private fun StudioHelperReceipt.automaticParents(receipts: Map<String, StudioHelperReceipt>): List<RequestId>? {
    val source = automaticSource
    if (source != null) {
        val parent = receipts[source.request.value]
        val isValid = parent?.isAutomaticConversationSource == true && parent.causes.orEmpty() == source.causes
        if (!isValid || !source.actionFences.areUnfenced(receipts)) return null
    }
    return listOfNotNull(continuationOf, source?.request)
}

/** Immutable snapshot taken by the journal owner under its mutex, also used for automatic questionnaire answers. */
internal fun captureConversationSource(
    record: StudioChatRecord,
    receipts: Map<String, StudioHelperReceipt>,
    request: RequestId,
    actions: Set<RequestId>,
): StudioConversationSource? {
    if (!record.isCompactable || record.lastRunRequest != request) return null
    val receipt = resolveContinuation(receipts, request)?.head ?: return null
    if (receipt.request != request || !receipt.isAutomaticConversationSource) return null
    if (!automaticAncestorsValid(receipts, request) || !actions.areUnfenced(receipts)) return null
    return StudioConversationSource(record.id, request, record.runRevision, receipt.causes.orEmpty(), actions)
}

private fun Set<RequestId>.areUnfenced(receipts: Map<String, StudioHelperReceipt>): Boolean =
    none { receipts[it.value]?.isContinuationFenced == true }
