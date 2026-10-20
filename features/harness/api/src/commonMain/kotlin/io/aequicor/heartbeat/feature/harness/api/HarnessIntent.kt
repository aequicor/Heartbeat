package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlin.time.Instant

/**
 * Library commands and correlated repository/runtime feedback. Other features may only connect chats and refresh
 * ([Public]); content, code and enabling change only through [Internal.Edit].
 */
public sealed interface HarnessIntent : MachineIntent {
    /** Commands whose host-generated request id correlates the durable outcome. */
    public sealed interface Command : HarnessIntent {
        public val requestId: RequestId
    }

    /** Commands any feature may send through the registry; none of them changes content, code or approval. */
    public sealed interface Public : Command {
        /** Connects a session; automatic review may fence the exact source revision and connection scope. */
        public data class Attach(
            override val requestId: RequestId,
            val id: HarnessId,
            val session: SessionRef,
            val expectedRevision: Long? = null,
            val expectedSessions: Set<SessionRef>? = null,
        ) : Public

        /** Removes a connection; optional expectations keep automatic review bound to its original scope. */
        public data class Detach(
            override val requestId: RequestId,
            val id: HarnessId,
            val session: SessionRef,
            val expectedRevision: Long? = null,
            val expectedSessions: Set<SessionRef>? = null,
        ) : Public

        /** Refreshes the snapshot without leaving Ready or cancelling pending writes. */
        public data class Reload(override val requestId: RequestId) : Public
    }

    /** This feature's edits, repository receipts and runtime lifecycle feedback; stale tokens are ignored. */
    public sealed interface Internal : HarnessIntent {
        /**
         * Edits of content, code and enabling. Activation compiles and publishes saved code, and
         * the machine never checks approval: only this feature's stores (direct user edits) and agent tools (after
         * automatic safety authorization) hold the full machine that accepts them.
         */
        public sealed interface Edit :
            Internal,
            Command

        /** Reserves a new immutable identity and name before saving. */
        public data class Create(
            override val requestId: RequestId,
            val id: HarnessId,
            val draft: HarnessDraft,
            val author: SessionRef?,
            val at: Instant,
        ) : Edit

        /** Applies an authorized edit only to the expected committed revision. */
        public data class Update(
            override val requestId: RequestId,
            val id: HarnessId,
            val change: HarnessChange,
            val expectedRevision: Long,
            val author: HarnessAuthor,
            val at: Instant,
            val expectedSessions: Set<SessionRef>? = null,
        ) : Edit

        /** Removes the harness through the durable cascade after revision validation. */
        public data class Delete(
            override val requestId: RequestId,
            val id: HarnessId,
            val expectedRevision: Long,
            val expectedSessions: Set<SessionRef>? = null,
        ) : Edit

        /** Persists the enabled flag; disabling cancels all runs owned by this harness. */
        public data class SetEnabled(
            override val requestId: RequestId,
            val id: HarnessId,
            val isEnabled: Boolean,
            val expectedRevision: Long,
            val at: Instant,
        ) : Edit

        /** Persists an item flag without changing pinned workflow runs. */
        public data class SetItemEnabled(
            override val requestId: RequestId,
            val id: HarnessId,
            val item: ItemId,
            val isEnabled: Boolean,
            val expectedRevision: Long,
            val at: Instant,
        ) : Edit

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
            val reason: HarnessValidationCode? = null,
        ) : Runtime

        /** An exact published instance failed, possibly before its activation receipt reaches the machine. */
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
