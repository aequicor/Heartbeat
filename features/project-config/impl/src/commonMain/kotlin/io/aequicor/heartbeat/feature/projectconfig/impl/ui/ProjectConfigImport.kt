package io.aequicor.heartbeat.feature.projectconfig.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ImportUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectConfigScreenIntent
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.Res
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_cancel
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_import_more
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_import_overwrite
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_import_skip
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_import_text
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_import_title
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

/** Asks whether a bundle replaces the units [import]'s project already has with other files, or keeps them. */
@Composable
internal fun ImportDialog(
    import: ImportUi,
    existing: ImmutableList<String>,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbDialog(
        stringResource(Res.string.pc_import_title),
        { onIntent(ProjectConfigScreenIntent.CancelImport) },
        modifier.testTag("project-config-import-dialog"),
        actions = {
            HbButton(
                stringResource(Res.string.pc_cancel),
                { onIntent(ProjectConfigScreenIntent.CancelImport) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.pc_import_skip),
                { onIntent(ProjectConfigScreenIntent.SkipExisting) },
                Modifier.testTag("project-config-import-skip"),
                style = HbButtonStyle.Secondary,
            )
            HbButton(
                stringResource(Res.string.pc_import_overwrite),
                { onIntent(ProjectConfigScreenIntent.OverwriteExisting) },
                Modifier.testTag("project-config-import-overwrite"),
                style = HbButtonStyle.Danger,
            )
        },
    ) {
        HbText(stringResource(Res.string.pc_import_text, import.projectName))
        existing.take(SHOWN_UNITS).forEach { Caption(it) }
        if (existing.size > SHOWN_UNITS) Caption(stringResource(Res.string.pc_import_more, existing.size - SHOWN_UNITS))
    }
}

private const val SHOWN_UNITS = 8
