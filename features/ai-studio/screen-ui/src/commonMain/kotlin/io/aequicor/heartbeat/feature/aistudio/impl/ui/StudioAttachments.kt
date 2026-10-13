package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbAttachmentThumbnail
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AttachmentPreviewUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AttachmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.InputSupportUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_file
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_incompatible
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_limit_exceeded
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_limits
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_remove
import io.aequicor.heartbeat.feature.aistudio.impl.resources.attachments_save
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource

/** Durable metadata; the same open action remains usable while new imports are disabled. */
@Composable
internal fun StudioAttachments(
    files: ImmutableList<AttachmentUi>,
    onIntent: (AiStudioScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
    paneId: Int? = null,
    support: InputSupportUi? = null,
    previews: ImmutableMap<String, AttachmentPreviewUi> = persistentMapOf(),
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        if (paneId != null) {
            HbLazyColumn(
                Modifier.heightIn(max = HbTheme.dimensions.composerMaxHeight).fillMaxWidth()
                    .testTag("draft-attachments-$paneId"),
                contentPadding = PaddingValues(HbTheme.spacing.none),
                gap = HbTheme.spacing.xs,
            ) {
                items(
                    files,
                    key = { it.id },
                ) { file -> AttachmentRow(file, onIntent, paneId, support, previews[file.id]) }
            }
        } else {
            HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
                files.forEach { file ->
                    key(file.id) {
                        AttachmentRow(file, onIntent, paneId, support, previews[file.id])
                    }
                }
            }
        }
        if (paneId != null && support != null) AttachmentLimits(files, support)
    }
}

@Composable
private fun AttachmentRow(
    file: AttachmentUi,
    onIntent: (AiStudioScreenIntent) -> Unit,
    paneId: Int?,
    support: InputSupportUi?,
    preview: AttachmentPreviewUi?,
) {
    val callback by rememberUpdatedState(onIntent)
    DisposableEffect(file.id, file.mediaType) {
        callback(AiStudioScreenIntent.AttachmentPreviewVisible(file.id, file.mediaType, true))
        onDispose { callback(AiStudioScreenIntent.AttachmentPreviewVisible(file.id, file.mediaType, false)) }
    }
    val name = file.name.ifBlank { stringResource(Res.string.attachments_file) }
    HbRow(gap = HbTheme.spacing.s) {
        HbSettingsRow(
            title = name,
            description = listOfNotNull(attachmentSize(file.sizeBytes), preview?.documentSnippet).joinToString(" · "),
            onClick = { onIntent(AiStudioScreenIntent.OpenAttachment(file.id)) },
            modifier = Modifier.weight(1f).testTag("attachment-${file.id}"),
            leadingContent = {
                HbAttachmentThumbnail(
                    preview?.imageBytes,
                    preview?.documentSnippet,
                    file.mediaType.startsWith("image/"),
                    isUnavailable = preview?.isUnavailable == true,
                    contentDescription = name,
                )
            },
        )
        if (paneId != null && support?.supportsFile(file) != true) {
            val unsupported = stringResource(Res.string.attachments_incompatible)
            HbTooltip(unsupported) {
                HbIcon(HbIcons.Warning, "$name: $unsupported", tint = HbTheme.colors.warning)
            }
        }
        if (paneId == null) {
            HbIconButton(
                HbIcons.Download,
                stringResource(Res.string.attachments_save),
                onClick = { onIntent(AiStudioScreenIntent.ExportAttachment(file.id)) },
            )
        } else {
            HbIconButton(
                HbIcons.Close,
                stringResource(Res.string.attachments_remove),
                onClick = { onIntent(AiStudioScreenIntent.RemoveAttachment(paneId, file.id)) },
                modifier = Modifier.testTag("attachment-remove-${file.id}"),
            )
        }
    }
}

@Composable
private fun AttachmentLimits(files: ImmutableList<AttachmentUi>, support: InputSupportUi) {
    HbColumn(gap = HbTheme.spacing.xs) {
        HbText(
            stringResource(
                Res.string.attachments_limits,
                support.maxAttachments,
                attachmentSize(support.maxFileBytes),
                attachmentSize(support.maxTotalBytes),
            ),
            color = HbTheme.colors.textSecondary,
            style = HbTheme.typography.caption,
        )
        if (!support.withinLimits(files)) {
            HbText(
                stringResource(Res.string.attachments_limit_exceeded),
                color = HbTheme.colors.warning,
                style = HbTheme.typography.caption,
            )
        }
    }
}

private fun attachmentSize(bytes: Long): String = when {
    bytes >= MEBIBYTE -> "${bytes / (MEBIBYTE)} MiB"
    bytes >= KIBIBYTE -> "${bytes / KIBIBYTE} KiB"
    else -> "$bytes B"
}

private const val KIBIBYTE = 1024
private const val MEBIBYTE = KIBIBYTE * KIBIBYTE
