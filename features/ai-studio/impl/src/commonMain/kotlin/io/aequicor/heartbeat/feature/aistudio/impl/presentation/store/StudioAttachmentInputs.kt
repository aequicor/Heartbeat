package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.InputSupportUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.NativeAttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.attachmentSupport
import io.aequicor.heartbeat.feature.aistudio.screenmodel.document
import io.aequicor.heartbeat.feature.aistudio.screenmodel.fileName
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isAttachmentPending
import io.aequicor.heartbeat.feature.aistudio.screenmodel.withPreviewVisibility
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsMachineKey
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import pro.respawn.flowmvi.api.PipelineContext
import kotlin.uuid.Uuid

private typealias AttachmentPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/** Correlated imports belong to the store lifetime, including a remote view's disconnected grace period. */
internal class StudioAttachmentInputs(
    private val machines: MachineRegistry,
    private val attachmentNavigation: MutableSharedFlow<StudioAttachmentNavigation>,
) {
    private val log = Log.tag("StudioAttachmentInputs")

    /** Keeps hot completion outputs subscribed even while no host collects the screen. */
    suspend fun observe(pipeline: AttachmentPipeline): Unit = with(pipeline) {
        machines.observe(AttachmentsMachineKey).collectLatest { ref ->
            ref?.outputs?.collect { output ->
                when (output) {
                    is AttachmentsOutput.Imported -> appendAttachments(
                        pipeline,
                        output.requestId,
                        output.attachments.map { it.toUi() }.toImmutableList(),
                    )

                    is AttachmentsOutput.Failed -> updateState { finish(output.requestId, isFailed = true) }

                    is AttachmentsOutput.Cancelled -> updateState { finish(output.requestId, isFailed = false) }

                    is AttachmentsOutput.Completed, is AttachmentsOutput.NativeRequested -> Unit
                }
            }
        }
    }

    /** Processes explicit input and native attachment navigation. */
    suspend fun handle(pipeline: AttachmentPipeline, intent: AiStudioScreenIntent.Attachment) = with(pipeline) {
        log.i { "Attachment action kind=${intent::class.simpleName.orEmpty()}" }
        when (intent) {
            is AiStudioScreenIntent.PickAttachments -> pickAttachments(pipeline, intent.paneId, null)

            is AiStudioScreenIntent.ImportAttachments -> pickAttachments(pipeline, intent.paneId, intent.inputs)

            is AiStudioScreenIntent.RemoveAttachment -> updateState {
                val key = draftKey(intent.paneId)
                copy(
                    draftAttachments = (
                        draftAttachments + (
                            key to attachments(intent.paneId)
                                .filterNot { it.id == intent.id }.toImmutableList()
                        )
                    ).toImmutableMap(),
                )
            }

            is AiStudioScreenIntent.OpenAttachment -> attachmentNavigation.emit(
                StudioAttachmentNavigation.Preview(intent.id),
            )

            is AiStudioScreenIntent.ExportAttachment -> attachmentNavigation.emit(
                StudioAttachmentNavigation.Preview(intent.id, true),
            )

            is AiStudioScreenIntent.AttachmentsSelected -> appendAttachments(pipeline, intent.requestId, intent.files)

            is AiStudioScreenIntent.AttachmentPreviewVisible -> updateState { withPreviewVisibility(intent) }
        }
    }

    private suspend fun pickAttachments(pipeline: AttachmentPipeline, paneId: Int, inputs: List<NativeAttachmentUi>?) =
        with(
            pipeline,
        ) {
            withState {
                if (!isAttachmentsEnabled || isAttachmentPending(paneId)) return@withState
                val support = attachmentSupport(paneId) ?: return@withState
                if (support.mediaTypes.isEmpty()) return@withState
                val requestId = Uuid.random().toString()
                val key = draftKey(paneId)
                updateState {
                    copy(
                        attachmentRequests = (
                            attachmentRequests.filterValues {
                                it != key
                            } + (requestId to key)
                        ).toImmutableMap(),
                        attachmentErrorPanes = (attachmentErrorPanes - paneId).toImmutableSet(),
                    )
                }
                if (inputs == null) {
                    log.i { "Open correlated attachment picker" }
                    attachmentNavigation.emit(
                        StudioAttachmentNavigation.Pick(requestId, support.toDomain()),
                    )
                } else {
                    captureAttachments(pipeline, requestId, support, inputs)
                }
            }
        }

    private suspend fun captureAttachments(
        pipeline: AttachmentPipeline,
        requestId: String,
        support: InputSupportUi,
        inputs: List<NativeAttachmentUi>,
    ) = with(pipeline) {
        val captured = inputs.map {
            when (it) {
                is NativeAttachmentUi.File -> AttachmentInput.File(it.location)

                is NativeAttachmentUi.Image -> AttachmentInput.Bytes("clipboard.png", "image/png", it.bytes)

                is NativeAttachmentUi.Page -> AttachmentInput.Bytes(
                    it.fileName(),
                    "text/markdown",
                    it.document().encodeToByteArray(),
                )
            }
        }
        val result = machines.send(
            AttachmentsMachineKey,
            AttachmentsIntent.Public.Import(requestId, captured, support.toDomain()),
        )
        if (result != SendResult.Accepted) {
            updateState { finish(requestId, isFailed = true) }
        }
    }

    private suspend fun appendAttachments(
        pipeline: AttachmentPipeline,
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
}

private fun AiStudioScreenState.finish(requestId: String, isFailed: Boolean): AiStudioScreenState {
    val key = attachmentRequests[requestId] ?: return this
    val affected = panes.filter { draftKey(it.id) == key }.map { it.id }
    return copy(
        attachmentRequests = (attachmentRequests - requestId).toImmutableMap(),
        attachmentErrorPanes = if (isFailed) {
            (attachmentErrorPanes + affected).toImmutableSet()
        } else {
            attachmentErrorPanes
        },
    )
}
