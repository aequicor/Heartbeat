package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbCodeEditor
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFieldUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigProblemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FieldEditorUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FieldInputUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_add_item
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_default_engine
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_managed
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_patterns_hint
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_remove_item
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_config_reset
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * One configuration value: a settings row with an inline control (flag, choice, number) or a label above a wide
 * editor (text, list, document, pairs, entries), then the problems found at it. Every change goes to [onInput];
 * [isAdvancedShown] unfolds the advanced fields of entries.
 */
@Composable
internal fun ConfigFieldRow(
    field: ConfigFieldUi,
    enabled: Boolean,
    onInput: (FieldInputUi) -> Unit,
    modifier: Modifier = Modifier,
    isAdvancedShown: Boolean = false,
) {
    val title = field.title.text()
    HbColumn(modifier.fillMaxWidth().testTag("config-field:${field.id}"), gap = HbTheme.spacing.xs) {
        when (val editor = field.editor) {
            is FieldEditorUi.Flag -> InlineField(field, enabled, onInput) {
                val isOn = editor.value ?: editor.default ?: false
                HbSwitch(
                    isOn,
                    { onInput(FieldInputUi.Flag(it)) },
                    title,
                    Modifier.testTag("config-flag:${field.id}"),
                    enabled,
                )
            }

            is FieldEditorUi.Choice -> InlineField(field, enabled, onInput) {
                ChoiceControl(title, editor, enabled, { onInput(FieldInputUi.Choice(it)) }, "config-choice:${field.id}")
            }

            is FieldEditorUi.Number -> InlineField(field, enabled, onInput) {
                NumberControl(title, editor, enabled, { onInput(FieldInputUi.Number(it)) }, "config-number:${field.id}")
            }

            is FieldEditorUi.Managed -> HbSettingsRow(title, description = editor.reason.text()) {
                HbBadge(stringResource(Res.string.control_config_managed))
            }

            is FieldEditorUi.KeyValues -> WideField(field, enabled, onInput) {
                KeyValuesEditor(field.id, editor, enabled, onInput)
            }

            is FieldEditorUi.Entries -> WideField(field, enabled, onInput) {
                EntriesEditor(field.id, editor, enabled, isAdvancedShown, onInput)
            }

            is FieldEditorUi.Text -> WideField(field, enabled, onInput) {
                HbTextField(
                    editor.text,
                    { onInput(FieldInputUi.Text(it)) },
                    Modifier.fillMaxWidth().testTag("config-text:${field.id}"),
                    placeholder = editor.default ?: stringResource(Res.string.control_config_default_engine),
                    enabled = enabled,
                    accessibleLabel = title,
                )
            }

            is FieldEditorUi.TextList -> WideField(field, enabled, onInput) {
                ListEditor(field.id, title, editor, enabled, { onInput(FieldInputUi.TextList(it)) })
            }

            is FieldEditorUi.Document -> WideField(field, enabled, onInput) {
                HbCodeEditor(
                    editor.text,
                    { onInput(FieldInputUi.Text(it)) },
                    title,
                    Modifier.fillMaxWidth().testTag("config-document:${field.id}"),
                    language = editor.language,
                    isReadOnly = !enabled,
                )
            }
        }
        FieldProblems(field.problems)
    }
}

/** A settings row whose trailing control edits the value. */
@Composable
private fun InlineField(
    field: ConfigFieldUi,
    enabled: Boolean,
    onInput: (FieldInputUi) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    HbSettingsRow(field.title.text(), modifier, description = field.description?.text()) {
        ResetButton(field, enabled, onInput)
        content()
    }
}

/** The title and description above an editor as wide as the column. */
@Composable
private fun WideField(
    field: ConfigFieldUi,
    enabled: Boolean,
    onInput: (FieldInputUi) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    HbColumn(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m), gap = HbTheme.spacing.xs) {
        HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            HbColumn(Modifier.weight(1f), gap = HbTheme.spacing.xxs) {
                HbText(field.title.text(), style = HbTheme.typography.label)
                field.description?.let {
                    HbText(it.text(), style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
                }
            }
            ResetButton(field, enabled, onInput)
        }
        content()
    }
}

/** Returns a set value to the engine's default. */
@Composable
private fun ResetButton(
    field: ConfigFieldUi,
    enabled: Boolean,
    onInput: (FieldInputUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!field.isSet) return
    HbIconButton(
        HbIcons.Undo,
        stringResource(Res.string.control_config_reset),
        { onInput(FieldInputUi.Reset) },
        modifier.testTag("config-reset:${field.id}"),
        enabled = enabled,
        size = HbTheme.dimensions.compactControlHeight,
    )
}

@Composable
private fun ChoiceControl(
    label: String,
    editor: FieldEditorUi.Choice,
    enabled: Boolean,
    onChoose: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val current = editor.selected ?: editor.default
    val items = editor.options.map { HbMenuItem(it.key, it.title.text(), isChecked = it.key == current) }
    HbMenuButton(
        HbIcons.ChevronDown,
        label,
        items.toImmutableList(),
        isExpanded,
        { isExpanded = it },
        onChoose,
        modifier.widthIn(max = HbTheme.dimensions.composerMenuMaxWidth).testTag(tag),
        enabled = enabled,
        label = editor.options.firstOrNull { it.key == current }?.title?.text()
            ?: stringResource(Res.string.control_config_default_engine),
    )
}

@Composable
private fun NumberControl(
    label: String,
    editor: FieldEditorUi.Number,
    enabled: Boolean,
    onType: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    HbRow(modifier, gap = HbTheme.spacing.xs) {
        HbTextField(
            editor.text,
            onType,
            Modifier.width(HbTheme.dimensions.composerMenuMinWidth).testTag(tag),
            placeholder = editor.default?.toString().orEmpty(),
            enabled = enabled,
            accessibleLabel = label,
        )
        editor.unit?.let {
            HbText(stringResource(it.label()), style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun ListEditor(
    id: String,
    label: String,
    editor: FieldEditorUi.TextList,
    enabled: Boolean,
    onChange: (ImmutableList<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        if (editor.isPatterns) {
            HbText(
                stringResource(Res.string.control_config_patterns_hint),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
        val remove = stringResource(Res.string.control_config_remove_item)
        editor.items.forEachIndexed { index, item ->
            HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
                HbTextField(
                    item,
                    { text ->
                        onChange(
                            editor.items.mapIndexed { at, old -> if (at == index) text else old }.toImmutableList(),
                        )
                    },
                    Modifier.weight(1f).testTag("config-item:$id:$index"),
                    enabled = enabled,
                    accessibleLabel = label,
                )
                HbIconButton(
                    HbIcons.Close,
                    remove,
                    { onChange(editor.items.filterIndexed { at, _ -> at != index }.toImmutableList()) },
                    Modifier.testTag("config-item-remove:$id:$index"),
                    enabled = enabled,
                    size = HbTheme.dimensions.compactControlHeight,
                )
            }
        }
        HbButton(
            stringResource(Res.string.control_config_add_item),
            { onChange((editor.items + "").toImmutableList()) },
            Modifier.testTag("config-item-add:$id"),
            HbButtonStyle.Ghost,
            enabled = enabled,
            size = HbButtonSize.Small,
        )
    }
}

/** The problems of a value, one line per reason; warnings do not block saving. */
@Composable
internal fun FieldProblems(problems: ImmutableList<ConfigProblemUi>, modifier: Modifier = Modifier) {
    if (problems.isEmpty()) return
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xxs) {
        problems.distinctBy { it.reason }.forEach { problem ->
            HbBanner(
                stringResource(ConfigProblemTexts.getValue(problem.reason)),
                tone = if (problem.isBlocking) HbTone.Danger else HbTone.Warning,
            )
        }
    }
}
