package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/** Committed content and ephemeral activation observations. Pending writes never replace [harness]. */
public data class HarnessEntry(val harness: Harness, val itemStatus: Map<ItemId, ItemStatus> = emptyMap())

/** Code activation is correlated by both committed revision and runtime generation. */
public sealed interface ItemStatus {
    /** Disabled by an item, harness or global switch. */
    public data object Disabled : ItemStatus

    /** Code execution is unavailable on this platform. */
    public data object Unsupported : ItemStatus

    /** The committed generation is awaiting atomic runtime activation. */
    public data class Pending(val generation: Long) : ItemStatus

    /** This runtime generation is available. */
    public data class Active(val generation: Long) : ItemStatus

    /** A failed activation or execution; isDisabled excludes further runtime use. */
    public data class Failed(val generation: Long, val isDisabled: Boolean) : ItemStatus
}

/** A load token captures the library version; concurrent writes make its snapshot inapplicable. */
public data class HarnessLoad(val generation: Long, val revision: Long, val isApplicable: Boolean = true)

/** Correlation of one durable write. Hosts must echo this exact receipt, not reconstruct it from current state. */
public data class HarnessReceipt(
    val requestId: RequestId,
    val id: HarnessId,
    val revision: Long,
    val generation: Long = 1,
)

/** One pending mutation per harness. Its proposed value reserves identity and storage capacity. */
public sealed interface HarnessMutation {
    public val receipt: HarnessReceipt

    /** A proposed committed revision, invisible until Saved. */
    public data class Save(override val receipt: HarnessReceipt, val harness: Harness, val isCreated: Boolean) :
        HarnessMutation

    /** An existing record retained while its removal cascade runs. */
    public data class Remove(override val receipt: HarnessReceipt, val harness: Harness) : HarnessMutation
}

/** Serialized attachment writes use a separate pending slot and leave harness revisions unchanged. */
public data class HarnessAttachmentWrite(
    val requestId: RequestId,
    val id: HarnessId,
    val session: SessionRef,
    val isAttached: Boolean,
    val attachments: Map<SessionRef, Set<HarnessId>>,
    val generation: Long = 1,
)

/** Approval changes become effective only after their exact write succeeds. */
public data class HarnessApprovalWrite(
    val requestId: RequestId,
    val level: HarnessApproval,
    val revision: Long,
    val generation: Long = 1,
)

/** Profile library. Durable data is loaded from the repository; pending IO is deliberately not persisted as state. */
public sealed interface HarnessState : MachineState {
    public val isSuspended: Boolean

    /** The profile library has not started loading. */
    public data class Idle(override val isSuspended: Boolean = false) : HarnessState

    /** Initial load with an exact correlation token. */
    public data class Loading(val load: HarnessLoad, override val isSuspended: Boolean = false) : HarnessState

    /** The initial repository load failed; Start retries with a fresh load generation. */
    public data class Failed(override val isSuspended: Boolean = false, val loadGeneration: Long = 0) : HarnessState

    /** Committed library plus separately correlated in-flight writes and runtime observations. */
    public data class Ready(
        val harnesses: List<HarnessEntry> = emptyList(),
        val attachments: Map<SessionRef, Set<HarnessId>> = emptyMap(),
        val approval: HarnessApproval = HarnessApproval.Ask,
        val isRuntimeAvailable: Boolean = false,
        override val isSuspended: Boolean = false,
        val revision: Long = 0,
        val approvalRevision: Long = 0,
        val pending: Map<HarnessId, HarnessMutation> = emptyMap(),
        val attachmentWrite: HarnessAttachmentWrite? = null,
        val approvalWrite: HarnessApprovalWrite? = null,
        val reload: HarnessLoad? = null,
        val loadGeneration: Long = 0,
        val activationGeneration: Long = 0,
        val writeGeneration: Long = 0,
    ) : HarnessState {
        public val isReloading: Boolean get() = reload != null
    }
}
