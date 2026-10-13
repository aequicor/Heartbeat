@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** UI metadata of one durable profile file; never contains its native source path. */
@Serializable
@SerialName("AttachmentUi")
@Immutable
data class AttachmentUi(val id: String, val name: String, val mediaType: String, val sizeBytes: Long)

/** UI-only reduced bytes and text; neither drafts nor persisted histories serialize this representation. */
@Serializable
@SerialName("AttachmentPreviewUi")
@Immutable
data class AttachmentPreviewUi(
    @Serializable(with = Base64BytesSerializer::class)
    val imageBytes: ByteArray? = null,
    val documentSnippet: String? = null,
    val isUnavailable: Boolean = false,
) {
    override fun toString(): String = "AttachmentPreviewUi(bytes=${imageBytes?.size ?: 0}, error=$isUnavailable)"
}

/** Multiple visible rows may share one stable attachment; only the last disposal releases its preview. */
@Serializable
@SerialName("PreviewVisibilityUi")
@Immutable
data class PreviewVisibilityUi(val mediaType: String, val consumers: Int)

/** Counts how many views show an attachment preview, so previews load only while visible. */
fun AiStudioScreenState.withPreviewVisibility(
    event: AiStudioScreenIntent.AttachmentPreviewVisible,
): AiStudioScreenState {
    val count = (visibleAttachmentPreviews[event.id]?.consumers ?: 0) + if (event.isVisible) 1 else -1
    val next = if (count <= 0) {
        visibleAttachmentPreviews - event.id
    } else {
        visibleAttachmentPreviews + (event.id to PreviewVisibilityUi(event.mediaType, count))
    }
    return copy(visibleAttachmentPreviews = next.toImmutableMap())
}

/** Exact model capability projected to immutable screen values. */
@Serializable
@SerialName("InputSupportUi")
@Immutable
data class InputSupportUi(
    val mediaTypes: ImmutableList<String> = persistentListOf(),
    val imageMediaTypes: ImmutableList<String> = persistentListOf(),
    val maxFileBytes: Long = DEFAULT_FILE_BYTES,
    val maxAttachments: Int = DEFAULT_FILE_COUNT,
    val maxTotalBytes: Long = DEFAULT_TOTAL_BYTES,
) {
    /** Validates current metadata against this exact model support and aggregate limits. */
    fun accepts(files: List<AttachmentUi>): Boolean = withinLimits(files) && files.all(::supportsFile)

    /** Individual incompatibility identifies the file that must be removed or sent with another model. */
    fun supportsFile(file: AttachmentUi): Boolean = file.mediaType in mediaTypes && file.sizeBytes <= maxFileBytes

    /** Message limits are displayed separately from individual format and file-size incompatibility. */
    fun withinLimits(files: List<AttachmentUi>): Boolean = files.size <= maxAttachments &&
        files.sumOf { it.sizeBytes } <= maxTotalBytes
}

/** Transient platform input captured only by an explicit drop or clipboard gesture. */
@Serializable
@SerialName("NativeAttachmentUi")
sealed interface NativeAttachmentUi {
    /** Native file drop location, forgotten as soon as it is imported. */
    @Serializable
    @SerialName("NativeAttachmentUi.File")
    data class File(val location: String) : NativeAttachmentUi {
        override fun toString(): String = "NativeAttachmentUi.File(redacted)"
    }

    /** Readable text captured from a browser page by the user's explicit gesture. */
    @Serializable
    @SerialName("NativeAttachmentUi.Page")
    data class Page(val url: String, val title: String = "", val text: String = "", val selection: String? = null) :
        NativeAttachmentUi {
        override fun toString(): String = "NativeAttachmentUi.Page(redacted)"
    }

    /** PNG image captured by an explicit clipboard gesture. */
    @Serializable
    @SerialName("NativeAttachmentUi.Image")
    data class Image(
        @Serializable(with = Base64BytesSerializer::class) val bytes: ByteArray,
    ) : NativeAttachmentUi {
        override fun toString(): String = "NativeAttachmentUi.Image(size=${bytes.size})"
    }
}

/**
 * Snapshot retained until native acceptance; a newer draft is never replaced or cleared. [harnesses] is the new
 * chat's harness choice that left the pane with it, returned to the pane when the submission is rejected.
 */
@Serializable
@SerialName("SubmissionUi")
@Immutable
data class SubmissionUi(
    val paneId: Int,
    val draftKey: String,
    val text: String,
    val attachments: ImmutableList<AttachmentUi>,
    val sessionId: String? = null,
    val isDisplayed: Boolean = true,
    val harnesses: ImmutableSet<String> = persistentSetOf(),
)

/** Creation binds a pending draft to its native session even when the pane later changes. */
fun AiStudioScreenState.prepareSubmission(id: String, sessionId: String): AiStudioScreenState {
    val submitted = submissions[id] ?: return this
    return copy(submissions = (submissions + (id to submitted.copy(sessionId = sessionId))).toImmutableMap())
}

/** The submission pane [paneId] would send now, with its draft and attachments under [id]. */
fun AiStudioScreenState.pendingSubmission(paneId: Int, id: String): SubmissionUi {
    val key = draftKey(paneId)
    val pane = panes.firstOrNull { it.id == paneId }
    val sessionId = pane?.sessionId
    return SubmissionUi(
        paneId,
        if (key.startsWith("pane:")) "submission:$id" else key,
        draft(paneId),
        attachments(paneId),
        sessionId = sessionId,
        harnesses = if (pane != null && sessionId == null) {
            val selected = harnessChoices.panes[paneId].orEmpty()
            availableHarnesses(pane).filter { it.id in selected }.map { it.id }.toImmutableSet()
        } else {
            persistentSetOf()
        },
    )
}

/** Moves only the owning new-page draft to a request key before the async machine operation starts. */
fun AiStudioScreenState.queueSubmission(id: String, pending: SubmissionUi): AiStudioScreenState {
    val currentKey = draftKey(pending.paneId)
    return copy(
        submissions = (submissions + (id to pending)).toImmutableMap(),
        drafts = ((drafts - currentKey) + (pending.draftKey to pending.text)).toImmutableMap(),
        draftAttachments = ((draftAttachments - currentKey) + (pending.draftKey to pending.attachments))
            .toImmutableMap(),
        attachmentRequests = attachmentRequests.mapValues { (_, key) ->
            if (key == currentKey) pending.draftKey else key
        }.toImmutableMap(),
    )
}

/** Clear exactly the accepted snapshot; editing during native validation preserves the newer draft. */
fun AiStudioScreenState.acceptSubmission(id: String, sessionId: String = ""): AiStudioScreenState {
    val submitted = submissions[id] ?: return this
    val text = if (drafts[submitted.draftKey] == submitted.text) drafts - submitted.draftKey else drafts
    val files = draftAttachments[submitted.draftKey].orEmpty()
        .filterNot { file -> submitted.attachments.any { it.id == file.id } }.toImmutableList()
    val nextKey = sessionId.ifBlank { submitted.sessionId.orEmpty() }.ifBlank { submitted.draftKey }
    val remainder = text[submitted.draftKey].orEmpty()
    val movedText = if (nextKey != submitted.draftKey && text[nextKey].isNullOrEmpty()) {
        (text - submitted.draftKey) + (nextKey to remainder)
    } else {
        text
    }
    val movedFiles = if (nextKey != submitted.draftKey) {
        val nextFiles = (draftAttachments[nextKey].orEmpty() + files).distinctBy { it.id }.toImmutableList()
        (draftAttachments - submitted.draftKey) + (nextKey to nextFiles)
    } else {
        draftAttachments + (submitted.draftKey to files)
    }
    return copy(
        drafts = movedText.toImmutableMap(),
        submissions = (submissions - id).toImmutableMap(),
        draftAttachments = movedFiles.toImmutableMap(),
        attachmentRequests = attachmentRequests.mapValues { (_, key) ->
            if (key == submitted.draftKey) nextKey else key
        }.toImmutableMap(),
    )
}

/**
 * Rejection never rewrites the current text or files. A new chat's draft returns to its pane together with the
 * harness choice that left with it, added to anything chosen there since.
 */
fun AiStudioScreenState.rejectSubmission(id: String, sessionId: String = ""): AiStudioScreenState {
    val submitted = submissions[id] ?: return this
    val nextKey = sessionId.ifBlank { submitted.sessionId.orEmpty() }.ifBlank {
        if (submitted.isDisplayed && submitted.draftKey.startsWith("submission:")) {
            "pane:${submitted.paneId}"
        } else {
            submitted.draftKey
        }
    }
    val nextDrafts = if (nextKey != submitted.draftKey && drafts[nextKey].isNullOrEmpty()) {
        (drafts - submitted.draftKey) + (nextKey to drafts[submitted.draftKey].orEmpty())
    } else {
        drafts
    }
    val nextFiles = if (nextKey != submitted.draftKey) {
        (draftAttachments - submitted.draftKey) +
            (
                nextKey to (draftAttachments[nextKey].orEmpty() + draftAttachments[submitted.draftKey].orEmpty())
                    .distinctBy { it.id }.toImmutableList()
            )
    } else {
        draftAttachments
    }
    return copy(
        submissions = (submissions - id).toImmutableMap(),
        drafts = nextDrafts.toImmutableMap(),
        draftAttachments = nextFiles.toImmutableMap(),
        harnessChoices = harnessChoices.restorePane(submitted, nextKey),
        attachmentRequests = attachmentRequests.mapValues { (_, key) ->
            if (key == submitted.draftKey) nextKey else key
        }.toImmutableMap(),
        failedPanes = (
            failedPanes +
                panes.filter { it.sessionId == nextKey || (submitted.isDisplayed && it.id == submitted.paneId) }
                    .map { it.id }
        ).toImmutableSet(),
    )
}

/** What the model of pane [paneId] accepts as attachments, or `null` when unknown. */
fun AiStudioScreenState.attachmentSupport(paneId: Int): InputSupportUi? {
    val sessionId = panes.firstOrNull { it.id == paneId }?.sessionId
    val modelId = organisms[sessionId]?.modelId ?: configurations[sessionId]?.modelId
        ?: session(sessionId)?.modelId ?: settings.modelId
    return models.firstOrNull { it.id == modelId }?.inputSupport
}

private const val DEFAULT_FILE_BYTES = 10L * 1024 * 1024
private const val DEFAULT_FILE_COUNT = 10
private const val DEFAULT_TOTAL_BYTES = 25L * 1024 * 1024

/** A draft cannot be submitted while its attachment request has not returned. */
fun AiStudioScreenState.isAttachmentPending(paneId: Int): Boolean = draftKey(paneId) in attachmentRequests.values
