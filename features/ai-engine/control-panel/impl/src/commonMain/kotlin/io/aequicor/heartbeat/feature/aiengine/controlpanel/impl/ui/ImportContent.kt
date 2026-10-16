package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportItemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportNoteReasonUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportNoteUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportReportUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ImportUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_cancel
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_close
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_confirm
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_count
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_description
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_dirty
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_done
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_empty
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_merge
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_note_host
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_note_large
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_note_owned
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_note_secret
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_note_skipped
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_note_unreadable
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_open
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_replace
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_source
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_import_title
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The overview's entry to an import of the engine's own configuration. */
@Composable
internal fun ImportSection(isEnabled: Boolean, onIntent: (ImportIntent) -> Unit, modifier: Modifier = Modifier) {
    HbSettingsSection(stringResource(Res.string.control_import_title), modifier.testTag("engine-control-import")) {
        HbSettingsRow(
            stringResource(Res.string.control_import_title),
            description = stringResource(Res.string.control_import_description),
        ) {
            HbButton(
                stringResource(Res.string.control_import_open),
                { onIntent(ImportIntent.Preview) },
                Modifier.testTag("import-open"),
                HbButtonStyle.Secondary,
                enabled = isEnabled,
                size = HbButtonSize.Small,
            )
        }
    }
}

/**
 * What an import would bring: the parts to choose with what it changes in each and how imported files combine with
 * the profile. Unsaved changes of the profile must be saved or discarded first.
 */
@Composable
internal fun ImportDialog(
    importing: ImportUi,
    isBlocked: Boolean,
    isSaving: Boolean,
    onIntent: (ImportIntent) -> Unit,
) {
    HbDialog(
        stringResource(Res.string.control_import_title),
        onDismissRequest = { onIntent(ImportIntent.Dismiss) },
        actions = {
            HbButton(
                stringResource(Res.string.control_cancel),
                { onIntent(ImportIntent.Dismiss) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.control_import_confirm),
                { onIntent(ImportIntent.Import) },
                Modifier.testTag("import-confirm"),
                style = HbButtonStyle.Primary,
                enabled = !isBlocked && !isSaving && importing.selected.isNotEmpty(),
            )
        },
    ) {
        HbColumn(Modifier.fillMaxWidth().testTag("import-dialog"), gap = HbTheme.spacing.m) {
            HbText(
                stringResource(Res.string.control_import_source, importing.source),
                color = HbTheme.colors.textSecondary,
            )
            if (isBlocked) HbBanner(stringResource(Res.string.control_import_dirty), tone = HbTone.Warning)
            if (importing.items.isEmpty()) HbText(stringResource(Res.string.control_import_empty))
            importing.items.forEach { item ->
                ImportItemRow(item, item.id in importing.selected, { onIntent(ImportIntent.Toggle(item.id)) })
            }
            if (importing.items.isNotEmpty()) {
                HbChoiceRow(
                    stringResource(Res.string.control_import_merge),
                    selected = !importing.isReplacing,
                    isMultiple = false,
                    onClick = { onIntent(ImportIntent.SetReplacing(false)) },
                    modifier = Modifier.testTag("import-merge"),
                )
                HbChoiceRow(
                    stringResource(Res.string.control_import_replace),
                    selected = importing.isReplacing,
                    isMultiple = false,
                    onClick = { onIntent(ImportIntent.SetReplacing(true)) },
                    modifier = Modifier.testTag("import-replace"),
                )
            }
        }
    }
}

/** What a finished import brought and what it changed or skipped. */
@Composable
internal fun ImportReportDialog(report: ImportReportUi, onIntent: (ImportIntent) -> Unit) {
    HbDialog(
        stringResource(Res.string.control_import_done),
        onDismissRequest = { onIntent(ImportIntent.Dismiss) },
        actions = {
            HbButton(
                stringResource(Res.string.control_import_close),
                { onIntent(ImportIntent.Dismiss) },
                Modifier.testTag("import-close"),
                style = HbButtonStyle.Primary,
            )
        },
    ) {
        HbColumn(Modifier.fillMaxWidth().testTag("import-report"), gap = HbTheme.spacing.xs) {
            report.imported.forEach { HbText(it.text()) }
            ImportNotes(report.notes)
        }
    }
}

@Composable
private fun ImportItemRow(
    item: ImportItemUi,
    isSelected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xxs) {
        val title = item.title.text()
        HbChoiceRow(
            item.count?.let { stringResource(Res.string.control_import_count, title, it) } ?: title,
            selected = isSelected,
            isMultiple = true,
            onClick = onToggle,
            modifier = Modifier.testTag("import-item:${item.id}"),
        )
        ImportNotes(item.notes)
    }
}

@Composable
private fun ImportNotes(notes: ImmutableList<ImportNoteUi>, modifier: Modifier = Modifier) {
    if (notes.isEmpty()) return
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xxs) {
        notes.distinctBy { it.reason to it.path }.forEach { note ->
            val text = stringResource(note.reason.text())
            HbText(
                if (note.path.isEmpty()) text else "$text: ${note.path}",
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
    }
}

private fun ImportNoteReasonUi.text(): StringResource = when (this) {
    ImportNoteReasonUi.OwnedKeyDropped -> Res.string.control_import_note_owned
    ImportNoteReasonUi.SecretMovedToVault -> Res.string.control_import_note_secret
    ImportNoteReasonUi.HostVariableNotPassed -> Res.string.control_import_note_host
    ImportNoteReasonUi.Skipped -> Res.string.control_import_note_skipped
    ImportNoteReasonUi.TooLarge -> Res.string.control_import_note_large
    ImportNoteReasonUi.Unreadable -> Res.string.control_import_note_unreadable
}
