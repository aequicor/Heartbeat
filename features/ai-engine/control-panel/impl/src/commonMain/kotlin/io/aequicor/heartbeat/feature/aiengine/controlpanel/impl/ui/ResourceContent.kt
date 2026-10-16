package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceFileUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceInventoryUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceKindUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceOriginUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ResourceStateUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resource_enabled
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resource_excluded
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resource_extension
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resource_kind_off
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resource_needs_trust
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resource_not_listed
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resource_profile
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resource_project
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resource_prompt
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resource_skill
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resources_empty
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resources_external
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resources_loading
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resources_note
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resources_refresh
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resources_title
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resources_truncated
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_resources_unloaded
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Saved resource selection in the host's scrolling column; long statuses wrap at compact widths. */
@Composable
internal fun ResourceContent(
    inventory: ResourceInventoryUi?,
    isLoading: Boolean,
    isRefreshEnabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.control_resources_title),
        modifier.fillMaxWidth().testTag("resource-inventory"),
        description = stringResource(Res.string.control_resources_note),
    ) {
        HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.m) {
            HbButton(
                stringResource(Res.string.control_resources_refresh),
                onRefresh,
                Modifier.testTag("resources-refresh"),
                HbButtonStyle.Ghost,
                enabled = isRefreshEnabled,
                size = HbButtonSize.Small,
            )
            when {
                isLoading -> HbLoadingState(stringResource(Res.string.control_resources_loading))
                inventory == null -> HbText(stringResource(Res.string.control_resources_unloaded))
                inventory.files.isEmpty() -> HbText(stringResource(Res.string.control_resources_empty))
            }
            inventory?.let { ResourceFiles(it) }
        }
    }
}

@Composable
private fun ResourceFiles(inventory: ResourceInventoryUi) {
    if (inventory.external > 0) HbText(stringResource(Res.string.control_resources_external, inventory.external))
    if (inventory.isTruncated) HbText(stringResource(Res.string.control_resources_truncated))
    inventory.files.forEachIndexed { index, file ->
        if (index > 0) HbDivider()
        ResourceRow(file)
    }
}

@Composable
private fun ResourceRow(file: ResourceFileUi, modifier: Modifier = Modifier) {
    val origin = if (file.origin == ResourceOriginUi.Profile) {
        Res.string.control_resource_profile
    } else {
        Res.string.control_resource_project
    }
    HbColumn(modifier.fillMaxWidth().testTag("resource:${file.origin}:${file.path}"), gap = HbTheme.spacing.xs) {
        HbText(file.name, maxLines = 1)
        HbText(
            "${stringResource(origin)} · ${stringResource(file.kind.label())} · ${stringResource(file.state.label())}",
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbText(file.path, style = HbTheme.typography.caption, maxLines = 1)
        file.description?.let { HbText(it, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary) }
    }
}

private fun ResourceKindUi.label(): StringResource = when (this) {
    ResourceKindUi.Extension -> Res.string.control_resource_extension
    ResourceKindUi.Skill -> Res.string.control_resource_skill
    ResourceKindUi.Prompt -> Res.string.control_resource_prompt
}

private fun ResourceStateUi.label(): StringResource = when (this) {
    ResourceStateUi.Enabled -> Res.string.control_resource_enabled
    ResourceStateUi.Excluded -> Res.string.control_resource_excluded
    ResourceStateUi.NotListed -> Res.string.control_resource_not_listed
    ResourceStateUi.NeedsTrust -> Res.string.control_resource_needs_trust
    ResourceStateUi.KindOff -> Res.string.control_resource_kind_off
}
