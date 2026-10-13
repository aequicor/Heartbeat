package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsMachineKey
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.collectLatest

/** Imports, failures and cancellations of the attachment requests of drafts, as the attachments machine reports. */
internal suspend fun observeAttachmentOutputs(machines: MachineRegistry, pipeline: StudioPipeline) {
    machines.observe(AttachmentsMachineKey).collectLatest { ref ->
        ref?.outputs?.collect { output ->
            when (output) {
                is AttachmentsOutput.Imported -> appendAttachments(
                    pipeline,
                    output.requestId,
                    output.attachments.map { it.toUi() }.toImmutableList(),
                )

                is AttachmentsOutput.Failed -> pipeline.updateState { withEndedRequest(output.requestId, true) }

                is AttachmentsOutput.Cancelled -> pipeline.updateState { withEndedRequest(output.requestId, false) }

                is AttachmentsOutput.Completed, is AttachmentsOutput.NativeRequested -> Unit
            }
        }
    }
}

/** Forgets the attachment request [id]; a failed one marks the panes of its draft. */
private fun AiStudioScreenState.withEndedRequest(id: String, isFailed: Boolean): AiStudioScreenState {
    val key = attachmentRequests[id]
    val affected = if (isFailed) panes.filter { draftKey(it.id) == key }.map { it.id } else emptyList()
    return copy(
        attachmentRequests = (attachmentRequests - id).toImmutableMap(),
        attachmentErrorPanes = (attachmentErrorPanes + affected).toImmutableSet(),
    )
}

/** Adds [files] imported for the request [requestId] to the draft that asked for them. */
internal suspend fun appendAttachments(
    pipeline: StudioPipeline,
    requestId: String,
    files: ImmutableList<AttachmentUi>,
) = with(
    pipeline,
) {
    updateState {
        val key = attachmentRequests[requestId] ?: return@updateState this
        val next = (draftAttachments[key].orEmpty() + files).distinctBy { it.id }.toImmutableList()
        copy(
            draftAttachments = (draftAttachments + (key to next)).toImmutableMap(),
            attachmentRequests = (attachmentRequests - requestId).toImmutableMap(),
        )
    }
}
