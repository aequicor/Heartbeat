package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.StateBuilder
import io.aequicor.heartbeat.core.statemachine.machineSpec

/**
 * Pure profile library. Each Ready transition stays in its state, so reload/suspension cannot cancel durable writes.
 * One harness write, one attachment write and one approval write may be pending in their respective domains.
 * Proposed records reserve names and storage quotas. Delete serializes with attachment writes to avoid lost updates.
 * The repository owns persistence; a new machine loads committed values and never replays an uncertain pending write.
 *
 * | From | Intent / guard | To | Effect / output |
 * |---|---|---|---|
 * | Idle, Failed | Start | Loading preserving suspension | Load |
 * | Loading | Loaded, matching valid snapshot | Ready preserving suspension | Activate enabled supported code |
 * | Loading | Loaded invalid / LoadFailed, matching | Failed | StorageFailed |
 * | Failed, not suspended | Reload (the screen's retry) | Loading | Load |
 * | Idle, Loading, Failed | any other Public | stay | Rejected(Unavailable) |
 * | Ready | Create valid unique and within reserved quotas | stay, pending save | Save |
 * | Ready | Update/SetEnabled/SetItemEnabled, current revision and valid | stay, pending save | Save |
 * | Ready | Delete, current revision and attachment domain free | stay, pending remove | Remove (cascade) |
 * | Ready | mutation stale revision / busy / missing / invalid / limits | stay | Rejected |
 * | Ready | Saved, exact pending receipt | stay, commit model | Activate/Deactivate, Created/Updated |
 * | Ready | Removed, exact pending receipt | stay, remove model and attachments | Deleted |
 * | Ready | SaveFailed, exact receipt | stay, preserve committed model | StorageFailed |
 * | Ready | RemoveFailed, exact receipt | stay, restore statuses at a new generation | Activate, StorageFailed |
 * | Ready | Attach/Detach, known id and domain free, within bounds | stay, pending attachment | SaveAttachments |
 * | Ready | AttachmentsSaved/Failed, exact | stay, commit/preserve map | Attached/Detached/StorageFailed |
 * | Ready | SetApproval, current approval revision and domain free | stay, pending approval | SaveApproval |
 * | Ready | ApprovalSaved/Failed, exact write | stay, commit/preserve approval | ApprovalChanged/StorageFailed |
 * | Ready | Reload, no pending reload | stay, isReloading | Load; concurrent writes continue |
 * | Ready | Loaded, exact reload and unchanged library without writes | stay, replace snapshot | Deactivate/Activate |
 * | Ready | Loaded, exact reload superseded by writes | stay, clear reload | none |
 * | Ready | invalid Loaded/LoadFailed, exact reload | stay, clear reload | StorageFailed |
 * | Ready | ItemActivated/ItemActivationFailed, exact pending generation+revision | stay | ItemActivated/ItemFailed |
 * | Ready | ActivationBatchFailed, matching pending items | stay | RuntimeFailed |
 * | Ready | ItemRuntimeFailed, exact live or pending generation+revision | stay | ItemFailed, optionally Deactivate |
 * | any | Suspended/Resumed, flag differs | stay, preserve data and pending writes | Ready: Deactivate/Activate |
 * | any | stale Internal, duplicate lifecycle flag, other unsupported Internal | ignored | none |
 *
 * Activation generation also fences late replies across suspend/resume of the same harness revision.
 * Runtime failures can precede the activation receipt; the later receipt cannot overwrite their Failed status.
 * Agent permission decisions happen before Public commands; the machine never treats author metadata as approval.
 */
public val HarnessMachineSpec: MachineSpec<HarnessState, HarnessIntent, HarnessEffect, HarnessOutput> =
    machineSpec(HarnessMachineKey, HarnessState.Idle()) {
        state<HarnessState.Idle> {
            unavailable()
            on<HarnessIntent.Internal.Start> {
                goto<HarnessState.Loading> { HarnessState.Loading(HarnessLoad(1, 0), state.isSuspended) }
                effect { HarnessEffect.Load(HarnessLoad(1, 0)) }
            }
            on<HarnessIntent.Internal.Suspended>(
                guard = { !state.isSuspended },
            ) { stay { state.copy(isSuspended = true) } }
            on<HarnessIntent.Internal.Resumed>(
                guard = { state.isSuspended },
            ) { stay { state.copy(isSuspended = false) } }
        }
        state<HarnessState.Failed> {
            unavailable(isRetry = { state, intent -> intent is HarnessIntent.Public.Reload && !state.isSuspended })
            on<HarnessIntent.Public.Reload>(guard = { !state.isSuspended }) {
                goto<HarnessState.Loading> { HarnessState.Loading(HarnessLoad(state.loadGeneration + 1, 0), false) }
                effect { HarnessEffect.Load(HarnessLoad(state.loadGeneration + 1, 0)) }
            }
            on<HarnessIntent.Internal.Start> {
                goto<HarnessState.Loading> {
                    HarnessState.Loading(
                        HarnessLoad(state.loadGeneration + 1, 0),
                        state.isSuspended,
                    )
                }
                effect { HarnessEffect.Load(HarnessLoad(state.loadGeneration + 1, 0)) }
            }
            on<HarnessIntent.Internal.Suspended>(
                guard = { !state.isSuspended },
            ) { stay { state.copy(isSuspended = true) } }
            on<HarnessIntent.Internal.Resumed>(
                guard = { state.isSuspended },
            ) { stay { state.copy(isSuspended = false) } }
        }
        state<HarnessState.Loading> {
            unavailable()
            on<HarnessIntent.Internal.Loaded>(guard = { intent.load == state.load && intent.isValid() }) {
                goto<HarnessState.Ready> { intent.ready(state.isSuspended) }
                effect {
                    intent.ready(
                        state.isSuspended,
                    ).activations().takeIf { it.isNotEmpty() }?.let(HarnessEffect::Activate)
                }
            }
            on<HarnessIntent.Internal.Loaded>(guard = { intent.load == state.load && !intent.isValid() }) {
                goto<HarnessState.Failed> { HarnessState.Failed(state.isSuspended, state.load.generation) }
                output { HarnessOutput.StorageFailed(null) }
            }
            on<HarnessIntent.Internal.LoadFailed>(guard = { intent.load == state.load }) {
                goto<HarnessState.Failed> { HarnessState.Failed(state.isSuspended, state.load.generation) }
                output { HarnessOutput.StorageFailed(null) }
            }
            on<HarnessIntent.Internal.Suspended>(
                guard = { !state.isSuspended },
            ) { stay { state.copy(isSuspended = true) } }
            on<HarnessIntent.Internal.Resumed>(
                guard = { state.isSuspended },
            ) { stay { state.copy(isSuspended = false) } }
        }
        state<HarnessState.Ready> {
            on<HarnessIntent.Public> {
                stay { state.command(intent).state }
                effect { state.command(intent).effects.getOrNull(0) }
                effect { state.command(intent).effects.getOrNull(1) }
                output { state.command(intent).output }
            }
            on<HarnessIntent.Internal>(guard = { state.feedback(intent) != null }) {
                stay { checkNotNull(state.feedback(intent)).state }
                effect { state.feedback(intent)?.effects?.getOrNull(0) }
                effect { state.feedback(intent)?.effects?.getOrNull(1) }
                output { state.feedback(intent)?.output }
            }
        }
        onEffectFailure { effect, _ -> effect.failure() }
    }

private fun <S : HarnessState> StateBuilder<
    HarnessState,
    S,
    HarnessIntent,
    HarnessEffect,
    HarnessOutput,
>.unavailable(
    isRetry: (S, HarnessIntent.Public) -> Boolean = { _, _ -> false },
) {
    on<HarnessIntent.Public>(guard = { !isRetry(state, intent) }) {
        output { HarnessOutput.Rejected(intent.requestId, HarnessRejection.Unavailable) }
    }
}

private fun HarnessEffect.failure(): HarnessIntent.Internal? = when (this) {
    is HarnessEffect.Load -> HarnessIntent.Internal.LoadFailed(load)

    is HarnessEffect.Save -> HarnessIntent.Internal.SaveFailed(receipt)

    is HarnessEffect.Remove -> HarnessIntent.Internal.RemoveFailed(receipt)

    is HarnessEffect.SaveAttachments -> HarnessIntent.Internal.AttachmentsFailed(write)

    is HarnessEffect.SaveApproval -> HarnessIntent.Internal.ApprovalFailed(write)

    is HarnessEffect.Activate -> HarnessIntent.Internal.ActivationBatchFailed(items)

    // No state waits for deactivation; runtime retains failed cleanup and logs/retries its owned barriers.
    is HarnessEffect.Deactivate -> null
}
