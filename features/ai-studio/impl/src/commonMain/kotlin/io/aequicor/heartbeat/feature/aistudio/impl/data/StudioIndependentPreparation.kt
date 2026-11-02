package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt

/** Pure independent receipt admission; the journal owner persists the result under its common mutex. */
internal fun prepareIndependentReceipt(
    records: Map<String, StudioHelperReceipt>,
    prompt: HelperPrompt,
    isRedelivery: Boolean,
    policy: StudioAttemptPolicy?,
    automaticSource: StudioConversationSource?,
): StudioHelperPreparation {
    val fingerprint = prompt.fingerprint()
    val existing = records[prompt.request.value]
    check(existing?.policy == null || existing.policy.hasSameOrigin(policy)) { "Request attribution cannot change" }
    check(existing == null || existing.automaticSource == automaticSource) { "Automatic source cannot change" }
    val isRedelivered = isRedelivery && existing?.policy == policy && existing?.isRedeliveryOf(prompt) == true
    if (existing != null && !isRedelivered) {
        check(
            existing.fingerprint == null ||
                (existing.fingerprint == fingerprint && existing.handoff == prompt.handoff),
        ) {
            "A helper request cannot change after it was journaled"
        }
        return StudioHelperPreparation(existing, isNew = false)
    }
    check(records.unresolvedHeads().none { it.request != prompt.request }) {
        "The previous helper request must be reconciled first"
    }
    val receipt = StudioHelperReceipt(
        prompt.request,
        StudioHelperPhase.Preparing,
        fingerprint,
        handoff = prompt.handoff,
        isNativeEntryTracked = true,
        policy = policy,
        automaticSource = automaticSource,
    )
    return StudioHelperPreparation(receipt, isNew = true)
}

/** Only its own unentered rollback permits redelivery; external Stop remains final. */
private fun StudioHelperReceipt.isRedeliveryOf(prompt: HelperPrompt): Boolean =
    phase in setOf(StudioHelperPhase.Preparing, StudioHelperPhase.NotSubmitted) &&
        !isFinal && fingerprint != null && handoff == prompt.handoff
