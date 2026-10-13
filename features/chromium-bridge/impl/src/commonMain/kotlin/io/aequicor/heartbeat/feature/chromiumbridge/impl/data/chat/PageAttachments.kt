package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsMachineKey
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsState
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.WirePage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

/** Result of importing pages. */
internal sealed interface PageImport {
    /** Attachments to send with the prompt, in the order of the pages. */
    data class Imported(val resources: List<ResourceRef>) : PageImport

    /** The attachments feature is busy with another operation or refused the pages. */
    data object Refused : PageImport
}

/** Stores captured pages as prompt attachments. */
internal fun interface PageImporter {
    /** Imports [pages]; an import nobody waits for any more is cancelled. */
    suspend fun import(pages: List<WirePage>): PageImport
}

/**
 * Turns captured pages into Markdown documents in the profile's attachments, so the prompt carries them like files
 * the user attached in the studio and the transcript shows them as attachments.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class PageAttachments(private val machines: MachineRegistry) : PageImporter {
    private val log = Log.tag("PageAttachments")

    override suspend fun import(pages: List<WirePage>): PageImport {
        if (pages.isEmpty()) return PageImport.Imported(emptyList())
        val machine = machines.find(AttachmentsMachineKey) ?: return refused("attachments are not running")
        machine.state.first { it !is AttachmentsState.Idle && it !is AttachmentsState.Preparing }
        val request = Uuid.random().toString()
        val inputs = pages.map { AttachmentInput.Bytes(fileName(it), MARKDOWN, pageDocument(it).encodeToByteArray()) }
        return coroutineScope {
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(IMPORT_TIMEOUT_MILLIS) { machine.outputs.first { it.isResultFor(request) } }
            }
            val sent = machines.send(AttachmentsMachineKey, AttachmentsIntent.Public.Import(request, inputs, SUPPORT))
            if (sent != SendResult.Accepted) {
                result.cancel()
                return@coroutineScope refused("import not accepted result=$sent")
            }
            var output: AttachmentsOutput? = null
            try {
                output = result.await()
            } finally {
                if (output == null) {
                    withContext(NonCancellable) {
                        machines.send(AttachmentsMachineKey, AttachmentsIntent.Public.Cancel(request))
                    }
                }
            }
            when (output) {
                is AttachmentsOutput.Imported -> {
                    log.i { "Pages imported count=${output.attachments.size}" }
                    PageImport.Imported(output.attachments.map { it.resource })
                }

                is AttachmentsOutput.Failed -> refused("import failed failure=${output.failure}")

                is AttachmentsOutput.Cancelled, is AttachmentsOutput.NativeRequested, is AttachmentsOutput.Completed,
                null,
                -> refused("import ended without attachments")
            }
        }
    }

    private fun refused(reason: String): PageImport {
        log.w { "Page attachments refused: $reason" }
        return PageImport.Refused
    }

    private companion object {
        const val MARKDOWN = "text/markdown"
        const val IMPORT_TIMEOUT_MILLIS = 30_000L
        const val MAX_NAME_CHARS = 80
        val SUPPORT = PromptInputSupport(resourceMediaTypes = setOf(MARKDOWN), maxAttachments = 4)

        fun fileName(page: WirePage): String {
            val base = page.title.ifBlank { page.url.substringAfter("://").substringBefore('/') }
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .trim()
                .take(MAX_NAME_CHARS)
                .ifBlank { "page" }
            return "$base.md"
        }
    }
}

/** The document the agent reads: where the page is, what the user selected and the page's readable text. */
internal fun pageDocument(page: WirePage): String = buildString {
    append("# ").append(page.title.ifBlank { page.url }.lineSequence().first()).append("\n\n")
    append("Source: ").append(page.url).append("\n")
    page.selection?.takeIf { it.isNotBlank() }?.let { selection ->
        append("\n## Selection\n\n").append(selection.trim()).append("\n")
    }
    if (page.text.isNotBlank()) append("\n## Page text\n\n").append(page.text.trim()).append("\n")
}

private fun AttachmentsOutput.isResultFor(request: String): Boolean = when (this) {
    is AttachmentsOutput.Imported -> requestId == request
    is AttachmentsOutput.Failed -> requestId == request
    is AttachmentsOutput.Cancelled -> requestId == request
    is AttachmentsOutput.NativeRequested -> requestId == request
    is AttachmentsOutput.Completed -> requestId == request
}
