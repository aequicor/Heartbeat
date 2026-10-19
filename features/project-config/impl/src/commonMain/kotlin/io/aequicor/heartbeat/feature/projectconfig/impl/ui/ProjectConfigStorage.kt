package io.aequicor.heartbeat.feature.projectconfig.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbMenu
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectConfigScreenIntent
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.StorageUi
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.Res
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_storage_app
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_storage_default
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_storage_hint
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_storage_inherit
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_storage_project
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_storage_title
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

/** Profile default; projects with explicit overrides keep their own location. */
@Composable
internal fun StorageSection(
    default: StorageUi,
    isBusy: Boolean,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.pc_storage_title),
        modifier.testTag("project-config-storage"),
        description = stringResource(Res.string.pc_storage_hint),
    ) {
        HbSettingsRow(stringResource(Res.string.pc_storage_default), Modifier.fillMaxWidth()) {
            StorageChoice(
                default,
                isInherited = false,
                canInherit = false,
                isEnabled = !isBusy,
                onChange = { selected ->
                    selected?.let { onIntent(ProjectConfigScreenIntent.SetDefaultStorage(it)) }
                },
                modifier = Modifier.testTag("project-config-default-storage"),
            )
        }
    }
}

/** A null override follows the profile default and can be restored after any explicit choice. */
@Composable
internal fun ProjectStorageRow(
    project: ProjectUi,
    isBusy: Boolean,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsRow(stringResource(Res.string.pc_storage_title), modifier.fillMaxWidth()) {
        StorageChoice(
            project.storage,
            isInherited = project.storageOverride == null,
            canInherit = true,
            isEnabled = !isBusy,
            onChange = { onIntent(ProjectConfigScreenIntent.SetProjectStorage(project.key, it)) },
            modifier = Modifier.testTag("project-config-storage-${project.key}"),
        )
    }
}

@Composable
private fun StorageChoice(
    storage: StorageUi,
    isInherited: Boolean,
    canInherit: Boolean,
    isEnabled: Boolean,
    onChange: (StorageUi?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isOpen by remember { mutableStateOf(false) }
    val project = stringResource(Res.string.pc_storage_project)
    val app = stringResource(Res.string.pc_storage_app)
    val inherit = stringResource(Res.string.pc_storage_inherit)
    val location = when (storage) {
        StorageUi.Project -> project
        StorageUi.App -> app
    }
    val title = if (isInherited) "$inherit: $location" else location
    val options = persistentListOf(HbMenuItem("project", project), HbMenuItem("app", app))
    val items = if (canInherit) persistentListOf(HbMenuItem("inherit", inherit)).addingAll(options) else options
    Box(modifier) {
        HbButton(
            title,
            { isOpen = true },
            style = HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
            enabled = isEnabled,
        )
        HbMenu(
            items,
            isOpen,
            { isOpen = false },
            { id ->
                isOpen = false
                when (id) {
                    "project" -> onChange(StorageUi.Project)
                    "app" -> onChange(StorageUi.App)
                    "inherit" -> onChange(null)
                }
            },
            stringResource(Res.string.pc_storage_title),
        )
    }
}
