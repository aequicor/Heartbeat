package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import io.aequicor.heartbeat.ds.components.HbAttachmentThumbnail
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchResourceUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchThumbnailUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResourceKindUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResourceScopeUi
import io.aequicor.heartbeat.feature.researchchat.impl.resources.Res
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_add_source
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_document
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_file_size
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_image
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_incompatible_source
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_no_sources
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_open_file
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_question_sources
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_remove_source
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_save_file
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_selected_sources
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_session_sources
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_share_source
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_source_import_error
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_sources_hint
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_use_source
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_website
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ResearchSources(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sharedCount = state.resources.count { it.isShared }
    val questionCount = state.resources.size - sharedCount
    val resources = state.resources.filter { it.isShared == (state.selectedSourceScope == ResourceScopeUi.Session) }
    Box(modifier.testTag("research-sources")) {
        HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
            HbRow(Modifier.selectableGroup().padding(horizontal = HbTheme.spacing.xs), gap = HbTheme.spacing.xxs) {
                HbNavigationItem(
                    label = stringResource(Res.string.research_session_sources, sharedCount),
                    onClick = { onIntent(ResearchScreenIntent.SelectSourceScope(ResourceScopeUi.Session)) },
                    modifier = Modifier.weight(1f).testTag("research-shared-sources"),
                    isSelected = state.selectedSourceScope == ResourceScopeUi.Session,
                    role = Role.Tab,
                )
                HbNavigationItem(
                    label = stringResource(Res.string.research_question_sources, questionCount),
                    onClick = { onIntent(ResearchScreenIntent.SelectSourceScope(ResourceScopeUi.Question)) },
                    modifier = Modifier.weight(1f).testTag("research-question-sources"),
                    isSelected = state.selectedSourceScope == ResourceScopeUi.Question,
                    role = Role.Tab,
                )
            }
            HbDivider()
            HbText(
                stringResource(Res.string.research_selected_sources, state.resources.count { it.isSelected }),
                Modifier.padding(horizontal = HbTheme.spacing.l, vertical = HbTheme.spacing.m),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
            HbLazyColumn(Modifier.weight(1f).fillMaxWidth(), gap = HbTheme.spacing.m) {
                if (resources.isEmpty()) {
                    item {
                        HbText(
                            stringResource(Res.string.research_no_sources),
                            color = HbTheme.colors.textSecondary,
                        )
                    }
                }
                items(resources, key = { it.id }) { resource ->
                    ResearchSourceRow(resource, state.isEditable, onIntent, state.thumbnails[resource.id])
                    HbDivider()
                }
            }
            HbText(
                stringResource(Res.string.research_sources_hint),
                Modifier.padding(horizontal = HbTheme.spacing.l),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
            HbButton(
                stringResource(Res.string.research_add_source),
                { onIntent(ResearchScreenIntent.ShowResourceDialog(true)) },
                Modifier.fillMaxWidth().padding(HbTheme.spacing.m).testTag("research-add-source"),
                style = HbButtonStyle.Secondary,
                enabled = state.isEditable,
            )
        }
    }
}

@Composable
private fun ResearchSourceRow(
    resource: ResearchResourceUi,
    isEditable: Boolean,
    onIntent: (ResearchScreenIntent) -> Unit,
    thumbnail: ResearchThumbnailUi?,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth().testTag("research-source-${resource.id}"), gap = HbTheme.spacing.xxs) {
        HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.xs, verticalAlignment = Alignment.Top) {
            HbSwitch(
                checked = resource.isSelected,
                onCheckedChange = { onIntent(ResearchScreenIntent.SetResourceSelected(resource.id, it)) },
                label = stringResource(Res.string.research_use_source, resource.title),
                modifier = Modifier.testTag("research-source-selected-${resource.id}"),
                enabled = isEditable,
            )
            if (resource.kind != ResourceKindUi.Website) {
                ResearchSourceThumbnail(resource, thumbnail, onIntent)
            }
            HbColumn(Modifier.weight(1f).padding(top = HbTheme.spacing.m), gap = HbTheme.spacing.xxs) {
                HbText(resource.title, style = HbTheme.typography.label, maxLines = 2)
                HbText(
                    resource.detail,
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                    maxLines = 2,
                )
                resource.sizeBytes?.let { size ->
                    HbText(
                        stringResource(Res.string.research_file_size, size),
                        style = HbTheme.typography.caption,
                        color = HbTheme.colors.textSecondary,
                    )
                }
            }
        }
        if (resource.hasImportError) {
            HbText(
                stringResource(Res.string.research_source_import_error),
                color = HbTheme.colors.error,
                style = HbTheme.typography.caption,
            )
        } else if (!resource.isCompatible) {
            HbText(
                stringResource(Res.string.research_incompatible_source),
                color = HbTheme.colors.error,
                style = HbTheme.typography.caption,
            )
        }
        HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
            HbIcon(resource.kind.icon(), null, tint = HbTheme.colors.textSecondary)
            HbText(resource.kind.label(), Modifier.weight(1f), style = HbTheme.typography.caption)
            resource.attachmentId?.let { id ->
                HbIconButton(
                    HbIcons.Eye,
                    stringResource(Res.string.research_open_file),
                    { onIntent(ResearchScreenIntent.OpenAttachment(id)) },
                )
                HbIconButton(
                    HbIcons.Download,
                    stringResource(Res.string.research_save_file),
                    { onIntent(ResearchScreenIntent.SaveAttachment(id)) },
                )
            }
            if (!resource.isShared) {
                HbIconButton(
                    icon = HbIcons.Share,
                    contentDescription = stringResource(Res.string.research_share_source),
                    onClick = { onIntent(ResearchScreenIntent.ShareResource(resource.id)) },
                    modifier = Modifier.testTag("research-share-source-${resource.id}"),
                    enabled = isEditable,
                )
            }
            HbIconButton(
                icon = HbIcons.Trash,
                contentDescription = stringResource(Res.string.research_remove_source),
                onClick = { onIntent(ResearchScreenIntent.RemoveResource(resource.id)) },
                modifier = Modifier.testTag("research-remove-source-${resource.id}"),
                enabled = isEditable,
            )
        }
    }
}

@Composable
private fun ResearchSourceThumbnail(
    resource: ResearchResourceUi,
    thumbnail: ResearchThumbnailUi?,
    onIntent: (ResearchScreenIntent) -> Unit,
) {
    val send by rememberUpdatedState(onIntent)
    DisposableEffect(resource.id, resource.attachmentId, resource.mediaType) {
        val id = resource.attachmentId
        val mime = resource.mediaType
        val isPreviewable = mime?.let { it.startsWith("image/") || it.startsWith("text/") } == true
        if (id != null && mime != null && isPreviewable) {
            send(ResearchScreenIntent.LoadThumbnail(resource.id, id, mime))
        }
        onDispose {
            if (id != null && mime != null && isPreviewable) send(ResearchScreenIntent.ReleaseThumbnail(resource.id))
        }
    }
    HbAttachmentThumbnail(
        thumbnail?.imageBytes,
        thumbnail?.documentSnippet,
        isImage = resource.kind == ResourceKindUi.Image,
        isUnavailable = thumbnail?.isUnavailable == true,
        contentDescription = resource.title,
    )
}

@Composable
internal fun ResourceKindUi.label(): String = stringResource(
    when (this) {
        ResourceKindUi.Website -> Res.string.research_website
        ResourceKindUi.Document -> Res.string.research_document
        ResourceKindUi.Image -> Res.string.research_image
    },
)

private fun ResourceKindUi.icon(): ImageVector = when (this) {
    ResourceKindUi.Website -> HbIcons.Globe
    ResourceKindUi.Document -> HbIcons.FileText
    ResourceKindUi.Image -> HbIcons.Image
}
