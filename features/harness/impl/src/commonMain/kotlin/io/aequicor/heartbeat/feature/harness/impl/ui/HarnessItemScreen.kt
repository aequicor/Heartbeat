package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbCodeEditor
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.harness.impl.presentation.DiagnosticUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessItemIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessItemModel
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessItemState
import io.aequicor.heartbeat.feature.harness.impl.presentation.ItemErrorUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ItemKindUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.PhaseUi
import io.aequicor.heartbeat.feature.harness.impl.resources.Res
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_cancel
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_conflict_overwrite
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_conflict_reload
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_conflict_text
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_conflict_title
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_diagnostic_error
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_diagnostic_warning
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_diagnostics
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_discard
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_dismiss
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_draft_restored
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_error_compile_timeout
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_error_input
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_error_too_long
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_field_content
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_field_description
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_field_input
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_save
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_save_failed
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_saving
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_unsupported_code
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableSet
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Editor of one item: Kotlin code with diagnostics or plain markdown text. */
@Composable
internal fun HarnessItemScreen(model: HarnessItemModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by produceState(HarnessItemState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    HarnessItemContent(state, model.store::intent, onBack, modifier)
}

/** Stateless editor; a conflicting agent edit asks whether to reload or overwrite. */
@Composable
internal fun HarnessItemContent(
    state: HarnessItemState,
    onIntent: (HarnessItemIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = if (state.name.isEmpty()) "" else "${state.harnessName}/${state.name}"
    HarnessPane(title, "harness-item", onBack, modifier) {
        if (!phaseItems(state.phase)) return@HarnessPane
        notices(state, onIntent)
        if (state.hasDescription) {
            item(key = "description") {
                HbTextField(
                    state.description,
                    { onIntent(HarnessItemIntent.ChangeDescription(it)) },
                    Modifier.fillMaxWidth().testTag("harness-item-description"),
                    placeholder = stringResource(Res.string.harness_field_description),
                    enabled = !state.isReadOnly,
                )
            }
        }
        item(key = "editor") { SourceEditor(state, onIntent) }
        if (state.hasInput) {
            item(key = "input") {
                HbSettingsSection(stringResource(Res.string.harness_field_input)) {
                    HbCodeEditor(
                        state.input,
                        { onIntent(HarnessItemIntent.ChangeInput(it)) },
                        stringResource(Res.string.harness_field_input),
                        Modifier.fillMaxWidth().height(HbTheme.dimensions.codeEditorCompactHeight)
                            .testTag("harness-item-input"),
                        language = "json",
                        isReadOnly = state.isReadOnly,
                        isError = state.error == ItemErrorUi.InvalidInput,
                    )
                }
            }
        }
        if (state.diagnostics.isNotEmpty()) {
            item(key = "diagnostics") { Diagnostics(state.diagnostics) }
        }
        item(key = "actions") { Actions(state, onIntent) }
    }
    if (state.isConflict) ConflictDialog(onIntent)
}

private fun LazyListScope.notices(state: HarnessItemState, onIntent: (HarnessItemIntent) -> Unit) {
    if (state.isReadOnly && state.isCode) {
        item(key = "unsupported") {
            HbBanner(stringResource(Res.string.harness_unsupported_code), tone = HbTone.Neutral)
        }
    }
    if (state.isDraftRestored && state.isDirty) {
        item(key = "draft") {
            HbBanner(
                stringResource(Res.string.harness_draft_restored),
                Modifier.testTag("harness-item-draft"),
                tone = HbTone.Warning,
            ) {
                HbButton(
                    stringResource(Res.string.harness_discard),
                    { onIntent(HarnessItemIntent.Discard) },
                    style = HbButtonStyle.Secondary,
                    size = HbButtonSize.Small,
                )
            }
        }
    }
    state.error?.let { error ->
        item(key = "error") {
            HbBanner(stringResource(error.message()), Modifier.testTag("harness-item-error")) {
                HbButton(
                    stringResource(Res.string.harness_dismiss),
                    { onIntent(HarnessItemIntent.DismissError) },
                    style = HbButtonStyle.Secondary,
                    size = HbButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun SourceEditor(
    state: HarnessItemState,
    onIntent: (HarnessItemIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorLines = remember(state.diagnostics) {
        state.diagnostics.filter { it.isError }.mapNotNull { it.line }.toImmutableSet()
    }
    HbCodeEditor(
        state.text,
        { onIntent(HarnessItemIntent.ChangeText(it)) },
        stringResource(Res.string.harness_field_content),
        modifier.fillMaxWidth().height(HbTheme.dimensions.codeEditorHeight).testTag("harness-item-editor"),
        language = if (state.isCode) "kotlin" else null,
        isReadOnly = state.isReadOnly,
        isError = errorLines.isNotEmpty(),
        errorLines = errorLines,
    )
}

@Composable
private fun Diagnostics(diagnostics: List<DiagnosticUi>, modifier: Modifier = Modifier) {
    HbSettingsSection(stringResource(Res.string.harness_diagnostics), modifier.testTag("harness-item-diagnostics")) {
        HbColumn(gap = HbTheme.spacing.xs) {
            diagnostics.forEach { diagnostic ->
                val place = listOfNotNull(diagnostic.line, diagnostic.column).joinToString(":")
                val text = listOf(place, diagnostic.message).filter(String::isNotEmpty).joinToString("  ")
                val severity = if (diagnostic.isError) {
                    Res.string.harness_diagnostic_error
                } else {
                    Res.string.harness_diagnostic_warning
                }
                HbText(
                    stringResource(severity, text),
                    style = HbTheme.typography.code,
                    color = if (diagnostic.isError) HbTheme.colors.textPrimary else HbTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun Actions(state: HarnessItemState, onIntent: (HarnessItemIntent) -> Unit, modifier: Modifier = Modifier) {
    HbRow(modifier, gap = HbTheme.spacing.s) {
        HbButton(
            stringResource(if (state.isSaving) Res.string.harness_saving else Res.string.harness_save),
            { onIntent(HarnessItemIntent.Save) },
            Modifier.testTag("harness-item-save"),
            enabled = state.isDirty && !state.isSaving && !state.isReadOnly,
        )
        if (state.isDirty) {
            HbButton(
                stringResource(Res.string.harness_discard),
                { onIntent(HarnessItemIntent.Discard) },
                Modifier.testTag("harness-item-discard"),
                style = HbButtonStyle.Ghost,
                enabled = !state.isSaving,
            )
        }
    }
}

@Composable
private fun ConflictDialog(onIntent: (HarnessItemIntent) -> Unit, modifier: Modifier = Modifier) {
    HbDialog(
        stringResource(Res.string.harness_conflict_title),
        { onIntent(HarnessItemIntent.DismissError) },
        modifier.testTag("harness-item-conflict"),
        actions = {
            HbButton(
                stringResource(Res.string.harness_cancel),
                { onIntent(HarnessItemIntent.DismissError) },
                style = HbButtonStyle.Ghost,
            )
            HbButton(
                stringResource(Res.string.harness_conflict_reload),
                { onIntent(HarnessItemIntent.Discard) },
                Modifier.testTag("harness-item-reload"),
                style = HbButtonStyle.Secondary,
            )
            HbButton(
                stringResource(Res.string.harness_conflict_overwrite),
                { onIntent(HarnessItemIntent.Overwrite) },
                Modifier.testTag("harness-item-overwrite"),
                style = HbButtonStyle.Danger,
            )
        },
    ) {
        HbText(stringResource(Res.string.harness_conflict_text))
    }
}

private fun ItemErrorUi.message() = when (this) {
    ItemErrorUi.SaveFailed -> Res.string.harness_save_failed
    ItemErrorUi.InvalidInput -> Res.string.harness_error_input
    ItemErrorUi.TooLong -> Res.string.harness_error_too_long
    ItemErrorUi.CompileTimedOut -> Res.string.harness_error_compile_timeout
}

private val previewState = HarnessItemState(
    phase = PhaseUi.Ready,
    harnessName = "compose_ui",
    name = "verify",
    kind = ItemKindUi.Script,
    text = "hooks.beforeTool { call ->\n    ToolHookVerdict.Continue\n}",
    description = "Blocks destructive commands",
    diagnostics = persistentListOf(DiagnosticUi(2, 5, "Unresolved reference: ToolHookVerdict", isError = true)),
    isDirty = true,
    isDraftRestored = true,
)

@Preview
@Composable
private fun HarnessItemLightPreview() {
    HbTheme(darkTheme = false) { HarnessItemContent(previewState, {}, {}) }
}

@Preview
@Composable
private fun HarnessItemDarkPreview() {
    HbTheme(darkTheme = true) {
        HarnessItemContent(previewState.copy(isReadOnly = true, diagnostics = persistentListOf()), {}, {})
    }
}
