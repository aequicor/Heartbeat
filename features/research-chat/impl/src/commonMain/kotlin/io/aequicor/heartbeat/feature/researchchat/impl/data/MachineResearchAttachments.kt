package io.aequicor.heartbeat.feature.researchchat.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.attachments.api.AttachmentDescriptor
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentImportPurpose
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsCatalog
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsMachineKey
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchAttachments
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

@Inject
@ContributesBinding(ProfileScope::class)
internal class MachineResearchAttachments(
    private val machines: MachineRegistry,
    private val catalog: AttachmentsCatalog,
) : ResearchAttachments {
    private val log = Log.tag("ResearchAttachments")

    override suspend fun import(
        inputs: List<AttachmentInput>,
        support: PromptInputSupport,
    ): List<AttachmentDescriptor> = importInputs(inputs, support, migration = false, sourceId = null)

    override suspend fun registered(id: AttachmentId): AttachmentDescriptor {
        log.d { "Resolve registered research source metadata" }
        return requireNotNull(catalog.get(id)) { "Missing research attachment" }
    }

    override suspend fun persist(
        source: ResearchResource,
        support: PromptInputSupport,
        migration: Boolean,
    ): ResearchResource {
        if (source.kind == ResearchResourceKind.Website || source.attachmentId != null ||
            source.value.startsWith("https://")
        ) {
            return source
        }
        if (source.value.startsWith("attachment:")) {
            val file = registered(AttachmentId(source.value.substringAfter(':')))
            return source.copy(attachmentId = file.id, attachmentSizeBytes = file.sizeBytes, hasAttachmentError = false)
        }
        val bytes = if (source.value.startsWith("data:")) {
            require(source.value.startsWith("data:${source.mediaType};base64,")) { "Invalid local source" }
            Base64.decode(source.value.substringAfter(','))
        } else {
            require(source.mediaType in setOf("text/plain", "text/markdown")) { "Invalid document source" }
            source.text.ifBlank { source.value }.encodeToByteArray()
        }
        log.i { "Persist local research source migration=$migration" }
        val file = importInputs(
            listOf(
                AttachmentInput.Bytes(
                    source.title.ifBlank { "source" } + extension(source.mediaType),
                    source.mediaType,
                    bytes,
                ),
            ),
            support,
            migration,
            source.id,
        ).single()
        return source.copy(
            value = file.resource.id,
            text = "",
            attachmentId = file.id,
            attachmentSizeBytes = file.sizeBytes,
            hasAttachmentError = false,
        )
    }

    private suspend fun importInputs(
        inputs: List<AttachmentInput>,
        support: PromptInputSupport,
        migration: Boolean,
        sourceId: String?,
    ): List<AttachmentDescriptor> = coroutineScope {
        val machine = checkNotNull(machines.find(AttachmentsMachineKey)) { "Attachment profile is not running" }
        machine.state.first { it !is AttachmentsState.Idle && it !is AttachmentsState.Preparing }
        val request = Uuid.random().toString()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.first {
                it.isResultFor(request)
            }
        }
        check(
            machines.send(
                AttachmentsMachineKey,
                AttachmentsIntent.Public.Import(
                    request,
                    inputs,
                    support,
                    if (migration) AttachmentImportPurpose.Migration else AttachmentImportPurpose.User,
                    if (migration) "research:${requireNotNull(sourceId)}" else null,
                ),
            ) == SendResult.Accepted,
        ) { "Research attachment import was not accepted" }
        when (val output = result.await()) {
            is AttachmentsOutput.Imported -> output.attachments
            else -> error("Research attachment import failed")
        }
    }

    private fun extension(mediaType: String): String = when (mediaType) {
        "text/markdown" -> ".md"
        "text/plain" -> ".txt"
        "application/pdf" -> ".pdf"
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        else -> ""
    }
}

/** Migration retains previously accepted formats regardless of the currently selected model. */
internal val LegacyResearchSupport = PromptInputSupport(
    imageMediaTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif"),
    resourceMediaTypes = setOf("text/plain", "text/markdown", "application/pdf"),
)

private fun AttachmentsOutput.isResultFor(request: String): Boolean = when (this) {
    is AttachmentsOutput.Imported -> requestId == request
    is AttachmentsOutput.Failed -> requestId == request
    is AttachmentsOutput.Cancelled -> requestId == request
    is AttachmentsOutput.NativeRequested, is AttachmentsOutput.Completed -> false
}
