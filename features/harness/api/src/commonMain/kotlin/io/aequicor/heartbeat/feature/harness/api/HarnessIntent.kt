package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlin.time.Instant

/** Authorized library commands and correlated repository/runtime feedback. */
public sealed interface HarnessIntent : MachineIntent {
    /** Commands whose host-generated request id correlates the durable outcome. */
    public sealed interface Public : HarnessIntent {
        public val requestId: RequestId

        /** Reserves a new immutable identity and name before saving. */
        public data class Create(
            override val requestId: RequestId,
            val id: HarnessId,
            val draft: HarnessDraft,
            val author: SessionRef?,
            val at: Instant,
        ) : Public

        /** Applies an authorized edit only to the expected committed revision. */
        public data class Update(
            override val requestId: RequestId,
            val id: HarnessId,
            val change: HarnessChange,
            val expectedRevision: Long,
            val author: HarnessAuthor,
            val at: Instant,
        ) : Public

        /** Removes the harness through the durable cascade after revision validation. */
        public data class Delete(override val requestId: RequestId, val id: HarnessId, val expectedRevision: Long) :
            Public

        /** Persists the enabled flag; disabling cancels all runs owned by this harness. */
        public data class SetEnabled(
            override val requestId: RequestId,
            val id: HarnessId,
            val isEnabled: Boolean,
            val expectedRevision: Long,
            val at: Instant,
        ) : Public

        /** Persists an item flag without changing pinned workflow runs. */
        public data class SetItemEnabled(
            override val requestId: RequestId,
            val id: HarnessId,
            val item: ItemId,
            val isEnabled: Boolean,
            val expectedRevision: Long,
            val at: Instant,
        ) : Public

        /** Connects a session independently of the harness revision. */
        public data class Attach(override val requestId: RequestId, val id: HarnessId, val session: SessionRef) :
            Public

        /** Removes an explicit session connection. */
        public data class Detach(override val requestId: RequestId, val id: HarnessId, val session: SessionRef) :
            Public

        /** [expectedRevision] refers to Ready.approvalRevision, not the library or harness revision. */
        public data class SetApproval(
            override val requestId: RequestId,
            val level: HarnessApproval,
            val expectedRevision: Long,
        ) : Public

        /** Refreshes the snapshot without leaving Ready or cancelling pending writes. */
        public data class Reload(override val requestId: RequestId) : Public
    }

    /** Repository receipts and runtime lifecycle feedback; stale tokens are ignored. */
    public sealed interface Internal : HarnessIntent {
        /** Receipts for durable library and setting IO. */
        public sealed interface Storage : Internal

        /** Feedback from code activation and execution. */
        public sealed interface Runtime : Internal

        /** Starts loading or retries a failed initial load. */
        public data object Start : Internal

        /** A coherent snapshot belonging to the exact load token. */
        public data class Loaded(
            val load: HarnessLoad,
            val harnesses: List<Harness>,
            val attachments: Map<SessionRef, Set<HarnessId>> = emptyMap(),
            val approval: HarnessApproval = HarnessApproval.Ask,
            val approvalRevision: Long = 0,
            val isRuntimeAvailable: Boolean = false,
        ) : Storage

        /** The matching snapshot read failed; exception text is not retained. */
        public data class LoadFailed(val load: HarnessLoad) : Storage

        /** The exact proposed harness revision is durably stored. */
        public data class Saved(val receipt: HarnessReceipt) : Storage

        /** The exact save failed; committed content remains effective. */
        public data class SaveFailed(val receipt: HarnessReceipt) : Storage

        /** The exact removal cascade and storage deletion completed. */
        public data class Removed(val receipt: HarnessReceipt) : Storage

        /** The exact deletion failed; retain the committed record. */
        public data class RemoveFailed(val receipt: HarnessReceipt) : Storage

        /** The exact proposed attachment map is durable. */
        public data class AttachmentsSaved(val write: HarnessAttachmentWrite) : Storage

        /** The attachment write failed; retain the old map. */
        public data class AttachmentsFailed(val write: HarnessAttachmentWrite) : Storage

        /** The exact approval value is durable. */
        public data class ApprovalSaved(val write: HarnessApprovalWrite) : Storage

        /** The approval write failed; retain the old value. */
        public data class ApprovalFailed(val write: HarnessApprovalWrite) : Storage

        /** The exact pending item revision and runtime generation became active. */
        public data class ItemActivated(
            val id: HarnessId,
            val item: ItemId,
            val revision: Long,
            val generation: Long,
        ) : Runtime

        /** The exact pending code generation could not replace its previous instance. */
        public data class ItemActivationFailed(
            val id: HarnessId,
            val item: ItemId,
            val revision: Long,
            val generation: Long,
        ) : Runtime

        /** An exact live instance failed; the runtime indicates whether its failure limit disabled it. */
        public data class ItemRuntimeFailed(
            val id: HarnessId,
            val item: ItemId,
            val revision: Long,
            val generation: Long,
            val isDisabled: Boolean,
        ) : Runtime

        /** Failure of a whole runtime batch, correlated separately for every item. */
        public data class ActivationBatchFailed(val items: List<HarnessActivationRequest>) : Runtime

        /** Globally removes effective contributions and pauses drivers, preserving committed records. */
        public data object Suspended : Internal

        /** Reactivates committed enabled items with fresh runtime generations. */
        public data object Resumed : Internal
    }
}
