package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/** One-shot receipts. A mutation is successful only after its durable effect replies. */
public sealed interface HarnessOutput : MachineOutput {
    /** A new harness is committed. */
    public data class Created(val requestId: RequestId, val id: HarnessId) : HarnessOutput

    /** A new harness revision is committed. */
    public data class Updated(val requestId: RequestId, val id: HarnessId) : HarnessOutput

    /** The removal cascade is committed. */
    public data class Deleted(val requestId: RequestId, val id: HarnessId) : HarnessOutput

    /** The explicit connection is committed. */
    public data class Attached(val requestId: RequestId, val id: HarnessId, val session: SessionRef) : HarnessOutput

    /** The explicit disconnection is committed. */
    public data class Detached(val requestId: RequestId, val id: HarnessId, val session: SessionRef) : HarnessOutput

    /** The approval setting is committed. */
    public data class ApprovalChanged(val requestId: RequestId, val level: HarnessApproval) : HarnessOutput

    /** A public request was refused without scheduling a write. */
    public data class Rejected(val requestId: RequestId, val reason: HarnessRejection) : HarnessOutput

    /** An exact code revision was published. */
    public data class ItemActivated(val id: HarnessId, val item: ItemId, val revision: Long) : HarnessOutput

    /** Activation or execution of an exact code revision failed. */
    public data class ItemFailed(val id: HarnessId, val item: ItemId, val revision: Long) : HarnessOutput

    /** A runtime batch failed; diagnostics stay in the runtime, without exception text in machine state. */
    public data class RuntimeFailed(val items: List<HarnessActivationRequest>) : HarnessOutput

    /** A repository operation failed; null request identifies an initial or reload read. */
    public data class StorageFailed(val requestId: RequestId?) : HarnessOutput
}

/** Profile-scoped library, started on first feature enable. */
public object HarnessMachineKey : MachineKey<
    HarnessState,
    HarnessIntent,
    HarnessIntent.Public,
    HarnessEffect,
    HarnessOutput,
> {
    override val name: String = "harness"
}
