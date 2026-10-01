package io.aequicor.heartbeat.feature.attachments.api

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport

/** Safe error categories; source names, paths and bytes are excluded. */
public enum class AttachmentFailure { Unavailable, Disabled, Unsupported, TooLarge, TooMany, Missing, Busy }

/** Distinguishes new user input from migration of an already saved source. */
public enum class AttachmentImportPurpose { User, Migration }

/** Platform operation performed only by a lifecycle-owned navigation component. */
public sealed interface AttachmentNativeOperation {
    /** Select multiple files using confirmed model support. */
    public data class Choose(public val support: PromptInputSupport) : AttachmentNativeOperation

    /** Open the app-owned copy in a system application. */
    public data class Open(public val id: AttachmentId) : AttachmentNativeOperation

    /** Save original bytes through the platform document dialog. */
    public data class Export(public val id: AttachmentId) : AttachmentNativeOperation
}

/** Request phase prevents duplicate picker feedback from starting a second import. */
public enum class AttachmentOperationPhase { Native, Importing }

/** Profile machine state contains no transient bytes or host locations. */
public sealed interface AttachmentsState : MachineState {
    /** Initial state before profile startup. */
    public data object Idle : AttachmentsState

    /** Opening the profile catalog and private storage. */
    public data object Preparing : AttachmentsState

    /** Accepts a new operation. */
    public data object Ready : AttachmentsState

    /** Exactly one active request; feedback must have the same identifier. */
    public data class Performing(
        public val requestId: String,
        public val phase: AttachmentOperationPhase = AttachmentOperationPhase.Native,
    ) : AttachmentsState

    /** Recoverable failure; new requests are accepted. */
    public data class Error(public val failure: AttachmentFailure) : AttachmentsState
}

/** Attachment commands and correlated lifecycle/effect feedback. */
public sealed interface AttachmentsIntent : MachineIntent {
    /** External commands. */
    public sealed interface Public : AttachmentsIntent {
        /** Starts the profile storage once. */
        public data object Start : Public

        /** Asks the active picker route to present native selection. */
        public data class Choose(public val requestId: String, public val support: PromptInputSupport) : Public

        /** Imports bounded user-selected inputs into profile storage. */
        public data class Import(
            public val requestId: String,
            public val inputs: List<AttachmentInput>,
            public val support: PromptInputSupport,
            public val purpose: AttachmentImportPurpose = AttachmentImportPurpose.User,
            public val deduplicationKey: String? = null,
        ) : Public {
            override fun toString(): String = "AttachmentsIntent.Import(requestId=$requestId, count=${inputs.size})"
        }

        /** Opens an existing attachment using the active preview route. */
        public data class Open(public val requestId: String, public val id: AttachmentId) : Public

        /** Exports original bytes using the active preview route. */
        public data class Export(public val requestId: String, public val id: AttachmentId) : Public

        /** Cancels only this request; stale cancellation does nothing. */
        public data class Cancel(public val requestId: String) : Public
    }

    /** Feedback is emitted only by impl effects or lifecycle-owned native routes. */
    public sealed interface Internal : AttachmentsIntent {
        /** Preparation finished. */
        public data object Prepared : Internal

        /** Preparation failed. */
        public data object PrepareFailed : Internal

        /** Native picker returned inputs; cancellation uses Public.Cancel. */
        public data class Selected(
            public val requestId: String,
            public val inputs: List<AttachmentInput>,
            public val support: PromptInputSupport,
        ) : Internal {
            override fun toString(): String = "AttachmentsIntent.Selected(requestId=$requestId, count=${inputs.size})"
        }

        /** Durable import completed. */
        public data class Imported(public val requestId: String, public val attachments: List<AttachmentDescriptor>) :
            Internal

        /** System open/export finished. */
        public data class NativeCompleted(public val requestId: String) : Internal

        /** A safe correlated error occurred. */
        public data class Failed(public val requestId: String, public val failure: AttachmentFailure) : Internal
    }
}

/** IO commands; transient input never enters a persistent machine state. */
public sealed interface AttachmentsEffect : MachineEffect {
    /** Initializes the owned repository. */
    public data object Prepare : AttachmentsEffect

    /** Copies and commits inputs atomically before returning metadata. */
    public data class Import(
        public val requestId: String,
        public val inputs: List<AttachmentInput>,
        public val support: PromptInputSupport,
        public val purpose: AttachmentImportPurpose = AttachmentImportPurpose.User,
        public val deduplicationKey: String? = null,
    ) : AttachmentsEffect {
        override fun toString(): String = "AttachmentsEffect.Import(requestId=$requestId, count=${inputs.size})"
    }
}

/** One-shot results; consumers must subscribe before sending a request. */
public sealed interface AttachmentsOutput : MachineOutput {
    /** Imported original bytes are now durable. */
    public data class Imported(public val requestId: String, public val attachments: List<AttachmentDescriptor>) :
        AttachmentsOutput

    /** Native operation must be handled by its route's current UI lifecycle. */
    public data class NativeRequested(public val requestId: String, public val operation: AttachmentNativeOperation) :
        AttachmentsOutput

    /** Request was explicitly cancelled. */
    public data class Cancelled(public val requestId: String) : AttachmentsOutput

    /** Correlated safe failure. */
    public data class Failed(public val requestId: String, public val failure: AttachmentFailure) : AttachmentsOutput

    /** Existing-file open/export finished. */
    public data class Completed(public val requestId: String) : AttachmentsOutput
}

/** Profile-scoped address, available after ProfileStartup even before any attachment screen opens. */
public object AttachmentsMachineKey :
    MachineKey<
        AttachmentsState,
        AttachmentsIntent,
        AttachmentsIntent.Public,
        AttachmentsEffect,
        AttachmentsOutput,
    > {
    override val name: String = "attachments"
}
