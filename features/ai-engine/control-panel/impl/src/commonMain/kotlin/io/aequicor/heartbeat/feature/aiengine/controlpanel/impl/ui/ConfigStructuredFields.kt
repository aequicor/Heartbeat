package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EntryUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FieldEditorUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FieldInputUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.KeyValueRowUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.SecretUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_add_entry
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_add_pair
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_entry_item
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_entry_name
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_entry_unknown
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_key
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_remove_entry
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_remove_item
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_secret_missing
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_secret_off
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_secret_on
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_secret_saved
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_value
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * Pairs of names and values. A value kept in the vault is never shown: its field is masked and only takes a new
 * value; the lock moves a value between the file and the vault.
 */
@Composable
internal fun KeyValuesEditor(
    id: String,
    label: String,
    editor: FieldEditorUi.KeyValues,
    enabled: Boolean,
    onInput: (FieldInputUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val change = { rows: List<KeyValueRowUi> -> onInput(FieldInputUi.KeyValues(rows.toImmutableList())) }
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        editor.rows.forEachIndexed { index, row ->
            PairRow(
                row,
                label,
                editor.isSecretAllowed,
                enabled,
                onChange = { change(editor.rows.mapIndexed { at, old -> if (at == index) it else old }) },
                onSecret = { onInput(FieldInputUi.SetSecret(row.key, it)) },
                onRemove = { change(editor.rows.filterIndexed { at, _ -> at != index }) },
                tag = "config-pair:$id:$index",
            )
        }
        HbButton(
            stringResource(Res.string.control_config_add_pair),
            { change(editor.rows + KeyValueRowUi()) },
            Modifier.testTag("config-pair-add:$id"),
            HbButtonStyle.Ghost,
            enabled = enabled,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun PairRow(
    row: KeyValueRowUi,
    label: String,
    isSecretAllowed: Boolean,
    enabled: Boolean,
    onChange: (KeyValueRowUi) -> Unit,
    onSecret: (Boolean) -> Unit,
    onRemove: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val isSecret = row.slot != null
    val keyLabel = stringResource(Res.string.control_config_key)
    val valueLabel = stringResource(Res.string.control_config_value)
    val key = @Composable { fieldModifier: Modifier ->
        HbTextField(
            row.key,
            { onChange(row.copy(key = it)) },
            fieldModifier.testTag("$tag:key"),
            placeholder = keyLabel,
            enabled = enabled,
            accessibleLabel = "$label: $keyLabel",
        )
    }
    val value = @Composable { fieldModifier: Modifier ->
        HbTextField(
            row.value,
            { onChange(row.copy(value = it)) },
            fieldModifier.testTag("$tag:value"),
            placeholder = when (row.secret) {
                SecretUi.Saved, SecretUi.Typed -> stringResource(Res.string.control_config_secret_saved)
                SecretUi.Missing -> stringResource(Res.string.control_config_secret_missing)
                null -> valueLabel
            },
            enabled = enabled,
            accessibleLabel = "$label: $valueLabel",
            isSecret = isSecret,
        )
    }
    if (HbTheme.dimensions.isDesktop) {
        HbRow(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            key(Modifier.weight(1f))
            value(Modifier.weight(1f))
            PairActions(isSecret, isSecretAllowed, row.key.isNotBlank(), enabled, onSecret, onRemove, tag)
        }
    } else {
        // Narrow touch screens stack the name above the value.
        HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
            key(Modifier.fillMaxWidth())
            HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
                value(Modifier.weight(1f))
                PairActions(isSecret, isSecretAllowed, row.key.isNotBlank(), enabled, onSecret, onRemove, tag)
            }
        }
    }
}

@Composable
private fun PairActions(
    isSecret: Boolean,
    isSecretAllowed: Boolean,
    hasKey: Boolean,
    enabled: Boolean,
    onSecret: (Boolean) -> Unit,
    onRemove: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    HbRow(modifier, gap = HbTheme.spacing.s) {
        if (isSecretAllowed) {
            HbIconButton(
                if (isSecret) HbIcons.Lock else HbIcons.Unlock,
                stringResource(
                    if (isSecret) Res.string.control_config_secret_off else Res.string.control_config_secret_on,
                ),
                { onSecret(!isSecret) },
                Modifier.testTag("$tag:secret"),
                enabled = enabled && hasKey,
                isSelected = isSecret,
                size = HbTheme.dimensions.compactControlHeight,
            )
        }
        HbIconButton(
            HbIcons.Close,
            stringResource(Res.string.control_config_remove_item),
            onRemove,
            Modifier.testTag("$tag:remove"),
            enabled = enabled,
            size = HbTheme.dimensions.compactControlHeight,
        )
    }
}

/**
 * Structured entries, each with its name (or position), the fields of its variant and a remove action; a new entry
 * takes one of the variants. Advanced fields of an entry show with [isAdvancedShown] or once set.
 */
@Composable
internal fun EntriesEditor(
    id: String,
    editor: FieldEditorUi.Entries,
    enabled: Boolean,
    isAdvancedShown: Boolean,
    onInput: (FieldInputUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        editor.items.forEachIndexed { index, entry ->
            if (index > 0) HbDivider()
            Entry(
                entry,
                index,
                editor,
                enabled,
                isAdvancedShown,
                onInput,
                Modifier.testTag("config-entry:$id:${entry.key}"),
            )
        }
        AddEntry(id, editor, enabled, onInput)
    }
}

@Composable
private fun Entry(
    entry: EntryUi,
    index: Int,
    editor: FieldEditorUi.Entries,
    enabled: Boolean,
    isAdvancedShown: Boolean,
    onInput: (FieldInputUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            if (editor.isNamed) {
                val nameLabel = stringResource(Res.string.control_config_entry_name)
                HbTextField(
                    entry.key,
                    { onInput(FieldInputUi.RenameEntry(entry.key, it)) },
                    Modifier.weight(1f).testTag("config-entry-name:${entry.key}"),
                    placeholder = nameLabel,
                    enabled = enabled,
                    accessibleLabel = "${editor.itemTitle.text()}: $nameLabel",
                )
            } else {
                HbText(
                    stringResource(Res.string.control_config_entry_item, index + 1),
                    Modifier.weight(1f),
                    style = HbTheme.typography.label,
                )
            }
            editor.variants.firstOrNull { it.key == entry.variant }?.let { HbBadge(it.title.text()) }
            HbIconButton(
                HbIcons.Trash,
                stringResource(Res.string.control_config_remove_entry),
                { onInput(FieldInputUi.RemoveEntry(entry.key)) },
                Modifier.testTag("config-entry-remove:${entry.key}"),
                enabled = enabled,
                size = HbTheme.dimensions.compactControlHeight,
            )
        }
        if (entry.variant == null) {
            HbText(
                stringResource(Res.string.control_config_entry_unknown),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        entry.fields.filter { isAdvancedShown || !it.isAdvanced || it.isSet || it.problems.isNotEmpty() }
            .forEach { field ->
                ConfigFieldRow(
                    field,
                    enabled,
                    { onInput(FieldInputUi.EditEntry(entry.key, field.id, it)) },
                    isAdvancedShown = isAdvancedShown,
                )
            }
        FieldProblems(entry.problems)
    }
}

@Composable
private fun AddEntry(
    id: String,
    editor: FieldEditorUi.Entries,
    enabled: Boolean,
    onInput: (FieldInputUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.control_config_add_entry, editor.itemTitle.text())
    val single = editor.variants.singleOrNull()
    if (single != null) {
        HbButton(
            label,
            { onInput(FieldInputUi.AddEntry(single.key)) },
            modifier.testTag("config-entry-add:$id"),
            HbButtonStyle.Ghost,
            enabled = enabled,
            size = HbButtonSize.Small,
        )
        return
    }
    var isExpanded by remember { mutableStateOf(false) }
    HbMenuButton(
        HbIcons.Plus,
        label,
        editor.variants.map { HbMenuItem(it.key, it.title.text()) }.toImmutableList(),
        isExpanded,
        { isExpanded = it },
        { onInput(FieldInputUi.AddEntry(it)) },
        modifier.widthIn(max = HbTheme.dimensions.composerMenuMaxWidth).testTag("config-entry-add:$id"),
        enabled = enabled,
        label = label,
    )
}
