package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect

/** Exact activation candidate; deactivation uses its generation as an inclusive upper-bound fence. */
public data class HarnessActivationRequest(val harness: Harness, val item: HarnessItem, val generation: Long)

/** Repository and runtime IO belong to impl. No effect is executed by this module. */
public sealed interface HarnessEffect : MachineEffect {
    /** Reads a coherent committed library, attachments and approval snapshot. */
    public data class Load(val load: HarnessLoad) : HarnessEffect

    /** Persists one proposed revision and echoes the receipt after durable completion. */
    public data class Save(val harness: Harness, val receipt: HarnessReceipt) : HarnessEffect

    /** Atomically removes storage and attachments after cancelling runs/wakes and draining owned calls. */
    public data class Remove(val harness: Harness, val receipt: HarnessReceipt) : HarnessEffect

    /** Persists the proposed attachment map and echoes the exact write. */
    public data class SaveAttachments(val write: HarnessAttachmentWrite) : HarnessEffect

    /** Persists the proposed approval value and echoes the exact write. */
    public data class SaveApproval(val write: HarnessApprovalWrite) : HarnessEffect

    /** Compiles and atomically publishes each exact item generation; reports one outcome per item. */
    public data class Activate(val items: List<HarnessActivationRequest>) : HarnessEffect

    /**
     * Revokes each (harness,item) instance with generation <= the request generation, including an old instance
     * retained after failed replacement. The fence also prevents an older pending activation from publishing later.
     * It never revokes a future generation. Harness revision is metadata, not an exact-match deactivation guard.
     * [harnesses] identifies drivers even when no code items remain: isStopping cancels their pinned runs;
     * otherwise global suspension pauses them. Item-only unloading uses an empty harness set.
     * [generation] fences harness drivers, including an empty [items] list: delayed cleanup must not affect
     * runs admitted after a newer enable/resume generation. Implementations retain the fence before awaiting IO.
     */
    public data class Deactivate(
        val items: List<HarnessActivationRequest>,
        val isStopping: Boolean,
        val harnesses: Set<HarnessId> = emptySet(),
        val generation: Long,
    ) : HarnessEffect
}
