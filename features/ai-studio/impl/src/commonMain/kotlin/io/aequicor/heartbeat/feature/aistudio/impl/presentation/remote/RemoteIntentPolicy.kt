package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.NativeAttachmentUi

/**
 * Whether a remote view may send [intent]. Refused: native desktop surfaces the browser cannot show (folder and file
 * pickers, opening or exporting an attachment, file paths of this computer, panes side by side) and the view's own
 * acknowledgement marker, which only the view session sends.
 */
internal fun isAllowedRemotely(intent: AiStudioScreenIntent): Boolean = when (intent) {
    is AiStudioScreenIntent.AddProject,
    is AiStudioScreenIntent.OpenBeside,
    is AiStudioScreenIntent.PickAttachments,
    is AiStudioScreenIntent.AttachmentsSelected,
    is AiStudioScreenIntent.OpenAttachment,
    is AiStudioScreenIntent.ExportAttachment,
    is AiStudioScreenIntent.RemoteApplied,
    -> false

    is AiStudioScreenIntent.ImportAttachments -> intent.inputs.none { it is NativeAttachmentUi.File }

    else -> true
}
