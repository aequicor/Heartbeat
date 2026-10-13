package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbCodeEditor
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.PlacementUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.DiagnosticUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.EditorAction
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.EditorIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.EditorModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.EditorPhase
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.EditorState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor.SurfaceInfoUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_cancel
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_changed
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_delete
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_delete_text
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_delete_title
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_enabled
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_error
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_keep_mine
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_keys
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_launchers
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_missing
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_owner
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_placement
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_preview
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_preview_behind
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_preview_hint
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_preview_none
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_problems
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_revert
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_revision
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_save
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_saving
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_spec
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_spec_invalid
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_use_saved
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_editor_warning
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_library_disabled
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_library_failed
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_loading
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_retry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Details, spec editor and preview of one library surface; leaving or deleting it calls [onBack]. */
@Composable
internal fun EditorScreen(
    model: EditorModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    val back by rememberUpdatedState(onBack)
    val state by produceState(EditorState(), model) {
        model.store.collect {
            launch { actions.collect { action -> if (action == EditorAction.Deleted) back() } }
            states.collect { value = it }
        }
    }
    val surfaces by model.surfaces.collectAsState()
    EditorContent(state, surfaces, model.store::intent, onBack, modifier, documents)
}

/** Stateless editor; deleting asks the user first. */
@Composable
internal fun EditorContent(
    state: EditorState,
    surfaces: ImmutableMap<String, SurfaceModel>,
    onIntent: (EditorIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    var isDeleting by remember { mutableStateOf(false) }
    ServiceUiPane(state.info.title, "service-ui-editor", onBack, modifier) {
        state.notice?.let { notice ->
            item(key = "notice") { WriteNoticeBanner(notice, { onIntent(EditorIntent.DismissNotice) }) }
        }
        if (!phaseItems(state.phase) { onIntent(EditorIntent.Reload) }) return@ServiceUiPane
        item(key = "info") { InfoSection(state.info, isWriting = state.isSaving, onIntent) }
        if (state.isChangedElsewhere) item(key = "changed") { ChangedBanner(onIntent) }
        item(key = "editor") { SpecEditor(state, onIntent) }
        if (state.diagnostics.isNotEmpty()) item(key = "problems") { Problems(state.diagnostics) }
        item(key = "preview") {
            PreviewSection(state.previewKey?.let { surfaces[it] }, state.isValid, documents = documents)
        }
        item(key = "delete") {
            HbButton(
                stringResource(Res.string.service_ui_editor_delete),
                { isDeleting = true },
                Modifier.testTag("service-ui-editor-delete"),
                style = HbButtonStyle.Danger,
                enabled = !state.isSaving,
            )
        }
    }
    if (isDeleting) {
        HbDialog(
            title = stringResource(Res.string.service_ui_editor_delete_title, state.info.title),
            onDismissRequest = { isDeleting = false },
            actions = {
                HbButton(
                    stringResource(Res.string.service_ui_cancel),
                    { isDeleting = false },
                    style = HbButtonStyle.Ghost,
                )
                HbButton(
                    stringResource(Res.string.service_ui_editor_delete),
                    {
                        isDeleting = false
                        onIntent(EditorIntent.Delete)
                    },
                    Modifier.testTag("service-ui-editor-delete-confirm"),
                    style = HbButtonStyle.Danger,
                )
            },
        ) { HbText(stringResource(Res.string.service_ui_editor_delete_text)) }
    }
}

private fun LazyListScope.phaseItems(phase: EditorPhase, onRetry: () -> Unit): Boolean {
    val title = when (phase) {
        EditorPhase.Ready -> return true

        EditorPhase.Loading -> {
            item(key = "loading") { HbLoadingState(stringResource(Res.string.service_ui_loading)) }
            return false
        }

        EditorPhase.Disabled -> Res.string.service_ui_library_disabled

        EditorPhase.Missing -> Res.string.service_ui_editor_missing

        EditorPhase.Failed -> Res.string.service_ui_library_failed
    }
    item(key = "phase") {
        HbEmptyState(
            stringResource(title),
            Modifier.testTag("service-ui-editor-${phase.name.lowercase()}"),
            action = if (phase == EditorPhase.Failed) {
                {
                    HbButton(
                        stringResource(Res.string.service_ui_retry),
                        onRetry,
                        Modifier.testTag("service-ui-editor-retry"),
                        style = HbButtonStyle.Secondary,
                        size = HbButtonSize.Small,
                    )
                }
            } else {
                null
            },
        )
    }
    return false
}

@Composable
private fun InfoSection(
    info: SurfaceInfoUi,
    isWriting: Boolean,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        info.title,
        modifier.testTag("service-ui-editor-info"),
        description = info.description.takeIf(String::isNotBlank),
    ) {
        InfoRow(stringResource(Res.string.service_ui_editor_owner), info.owner)
        HbDivider()
        InfoRow(stringResource(Res.string.service_ui_editor_placement), stringResource(info.placement.title()))
        if (info.launchers.isNotEmpty()) {
            HbDivider()
            val launchers = info.launchers.map { stringResource(it.title()) }.joinToString(", ")
            InfoRow(stringResource(Res.string.service_ui_editor_launchers), launchers)
        }
        HbDivider()
        InfoRow(stringResource(Res.string.service_ui_editor_revision), info.revision.toString())
        HbDivider()
        val label = stringResource(Res.string.service_ui_editor_enabled)
        HbSettingsRow(label, Modifier.fillMaxWidth()) {
            HbSwitch(
                info.isEnabled,
                { onIntent(EditorIntent.SetEnabled(it)) },
                label,
                Modifier.testTag("service-ui-editor-enabled"),
                enabled = !isWriting,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    // The value goes below the label: owners and launcher lists can be longer than a phone row.
    HbSettingsRow(label, modifier.fillMaxWidth(), description = value)
}

@Composable
private fun ChangedBanner(onIntent: (EditorIntent) -> Unit, modifier: Modifier = Modifier) {
    // The buttons wrap below the message: two of them beside it would leave the message no width on a phone.
    HbColumn(modifier.fillMaxWidth().testTag("service-ui-editor-changed"), gap = HbTheme.spacing.s) {
        HbBanner(stringResource(Res.string.service_ui_editor_changed), tone = HbTone.Warning)
        HbFlowRow {
            HbButton(
                stringResource(Res.string.service_ui_editor_use_saved),
                { onIntent(EditorIntent.Revert) },
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
            HbButton(
                stringResource(Res.string.service_ui_editor_keep_mine),
                { onIntent(EditorIntent.KeepDraft) },
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }
    }
}

@Composable
private fun SpecEditor(state: EditorState, onIntent: (EditorIntent) -> Unit, modifier: Modifier = Modifier) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.m) {
        HbCodeEditor(
            state.text,
            { onIntent(EditorIntent.ChangeText(it)) },
            stringResource(Res.string.service_ui_editor_spec),
            Modifier.fillMaxWidth().height(HbTheme.dimensions.codeEditorHeight).testTag("service-ui-editor-spec"),
            language = "json",
            isError = state.isChecked && !state.isValid,
            errorMessage = stringResource(Res.string.service_ui_editor_spec_invalid),
            keyboardHint = stringResource(Res.string.service_ui_editor_keys),
        )
        HbFlowRow {
            val isSavable = state.isChecked && state.isValid && state.isDirty && !state.isChangedElsewhere
            HbButton(
                stringResource(
                    if (state.isSaving) Res.string.service_ui_editor_saving else Res.string.service_ui_editor_save,
                ),
                { onIntent(EditorIntent.Save) },
                Modifier.testTag("service-ui-editor-save"),
                enabled = isSavable && !state.isSaving,
            )
            HbButton(
                stringResource(Res.string.service_ui_editor_revert),
                { onIntent(EditorIntent.Revert) },
                style = HbButtonStyle.Secondary,
                enabled = state.isDirty && !state.isSaving,
            )
        }
    }
}

@Composable
private fun Problems(diagnostics: ImmutableList<DiagnosticUi>, modifier: Modifier = Modifier) {
    HbSettingsSection(
        stringResource(Res.string.service_ui_editor_problems),
        modifier.testTag("service-ui-editor-problems"),
    ) {
        HbColumn(gap = HbTheme.spacing.xs) {
            diagnostics.forEach { diagnostic ->
                val text = "${diagnostic.path}: ${diagnostic.message}"
                val severity = if (diagnostic.isWarning) {
                    Res.string.service_ui_editor_warning
                } else {
                    Res.string.service_ui_editor_error
                }
                HbText(
                    stringResource(severity, text),
                    style = HbTheme.typography.code,
                    color = if (diagnostic.isWarning) HbTheme.colors.textSecondary else HbTheme.colors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun PreviewSection(
    preview: SurfaceModel?,
    isValid: Boolean,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    HbSettingsSection(
        stringResource(Res.string.service_ui_editor_preview),
        modifier.testTag("service-ui-editor-preview"),
        description = stringResource(Res.string.service_ui_editor_preview_hint),
    ) {
        if (preview == null) {
            HbText(
                stringResource(Res.string.service_ui_editor_preview_none),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        } else {
            if (!isValid) {
                HbText(
                    stringResource(Res.string.service_ui_editor_preview_behind),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
            }
            // A surface draws without a scroll of its own, so it fits the lazy list of the pane. A web document in the
            // preview reloads when the preview scrolls back in: the list disposes items that leave the screen.
            HbCard(
                Modifier.fillMaxWidth(),
                contentPadding = HbTheme.spacing.l,
            ) { SurfaceScreen(preview, documents = documents) }
        }
    }
}

private val previewState = EditorState(
    phase = EditorPhase.Ready,
    info = SurfaceInfoUi(
        title = "PR map",
        description = "Dependencies and priorities",
        owner = "PR review",
        placement = PlacementUi.Panel,
        launchers = persistentListOf(PlacementUi.Composer),
        revision = 4,
        isEnabled = true,
    ),
    text = "{\n    \"version\": 1,\n    \"body\": { \"type\": \"text\", \"text\": \"Hello\" }\n}",
    diagnostics = persistentListOf(DiagnosticUi("$.data.prs", "unknown method (list_prs)", isWarning = true)),
    isValid = true,
    isDirty = true,
    isChecked = true,
)

@Preview
@Composable
private fun EditorLightPreview() {
    HbTheme(darkTheme = false) { EditorContent(previewState, persistentMapOf(), {}, {}) }
}

@Preview
@Composable
private fun EditorDarkPreview() {
    HbTheme(darkTheme = true) {
        EditorContent(previewState.copy(isValid = false, isChangedElsewhere = true), persistentMapOf(), {}, {})
    }
}
