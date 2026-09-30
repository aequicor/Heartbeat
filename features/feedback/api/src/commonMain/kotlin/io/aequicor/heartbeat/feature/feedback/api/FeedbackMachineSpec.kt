package io.aequicor.heartbeat.feature.feedback.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Pure profile reporting machine. Executors publish only acknowledged outcomes; this machine never applies
 * settings or starts native work. The impl journal restores and saves its entries independently of screens.
 *
 * | From | Intent | Guard | To | Output |
 * |---|---|---|---|---|
 * | Loading | Publish | new id or newer revision of the same source | Loading (upsert queued) | |
 * | Loading | Loaded | | Ready (stored then queued, pending stored entries become Unknown) | |
 * | Loading | LoadFailed | | Ready (queued) | StorageFailed |
 * | Ready | Publish | new id or newer revision of the same source | Ready (upsert in original position) | |
 * | Ready | Loaded | | Ready (recovered stored entries then newer live entries) | |
 * | Ready | SaveFailed | | Ready | StorageFailed |
 *
 * Duplicate/stale revisions and an attempt to reassign an operation to another source are ignored. A loaded
 * Pending entry becomes Unknown with a higher revision, preventing a restart from implying success or retrying
 * a native change. Live entries win ties with restored records and keep their anchors unchanged.
 *
 * ```mermaid
 * stateDiagram-v2
 *     [*] --> Loading
 *     Loading --> Loading: Publish
 *     Loading --> Ready: Loaded / LoadFailed
 *     Ready --> Ready: Publish / Loaded / SaveFailed
 * ```
 */
public val FeedbackMachineSpec: MachineSpec<FeedbackState, FeedbackIntent, FeedbackEffect, FeedbackOutput> =
    machineSpec(FeedbackMachineKey, FeedbackState.Loading()) {
        state<FeedbackState.Loading> {
            on<FeedbackIntent.Public.Publish>(guard = { state.queued.accepts(intent.record) }) {
                stay { state.copy(queued = state.queued.upsert(intent.record)) }
            }
            on<FeedbackIntent.Internal.Loaded> {
                goto<FeedbackState.Ready> { FeedbackState.Ready(mergeRestored(intent.records, state.queued)) }
            }
            on<FeedbackIntent.Internal.LoadFailed> {
                goto<FeedbackState.Ready> { FeedbackState.Ready(state.queued) }
                output { FeedbackOutput.StorageFailed }
            }
        }
        state<FeedbackState.Ready> {
            on<FeedbackIntent.Public.Publish>(guard = { state.records.accepts(intent.record) }) {
                stay { state.copy(records = state.records.upsert(intent.record)) }
            }
            on<FeedbackIntent.Internal.Loaded> {
                stay { state.copy(records = mergeRestored(intent.records, state.records)) }
            }
            on<FeedbackIntent.Internal.SaveFailed> { output { FeedbackOutput.StorageFailed } }
        }
    }

private fun List<FeedbackRecord>.accepts(record: FeedbackRecord): Boolean {
    val previous = firstOrNull { it.id == record.id } ?: return true
    return previous.source == record.source && record.revision > previous.revision
}

private fun List<FeedbackRecord>.upsert(record: FeedbackRecord): List<FeedbackRecord> {
    val index = indexOfFirst { it.id == record.id }
    if (index < 0) return this + record
    return mapIndexed { current, previous ->
        if (current == index) record.copy(createdAt = previous.createdAt, anchor = previous.anchor) else previous
    }
}

private fun mergeRestored(stored: List<FeedbackRecord>, live: List<FeedbackRecord>): List<FeedbackRecord> {
    val records = stored.fold(emptyList<FeedbackRecord>()) { current, record ->
        val settled = if (record.outcome == FeedbackOutcome.Pending) {
            record.copy(
                revision = if (record.revision == Long.MAX_VALUE) record.revision else record.revision + 1,
                outcome = FeedbackOutcome.Unknown,
            )
        } else {
            record
        }
        if (current.accepts(settled)) current.upsert(settled) else current
    }
    return live.fold(records) { current, record ->
        val previous = current.firstOrNull { it.id == record.id }
        when {
            previous == null -> current + record
            previous.source == record.source && record.revision >= previous.revision -> current.upsert(record)
            else -> current
        }
    }
}
