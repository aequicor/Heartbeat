package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.FormError
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.conn_cancel
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_edit_conflict
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_edit_connection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_edit_description
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_edit_failed
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_edit_host
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_edit_key_hint
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_edit_key_placeholder
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_save
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.settings_saving
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_error_insecure_host
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_error_invalid_host
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_error_missing_key
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_field_key
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_field_label
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_hide_key
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.wizard_show_key
import org.jetbrains.compose.resources.stringResource

/** Local form for an existing source. The current key is never loaded into the UI. */
@Composable
internal fun ConnectionEditContent(
    state: EngineConnectionsScreenState,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.editDraft ?: return
    val isEnabled = !state.isSaving
    val labelFocus = remember(draft.binding) { FocusRequester() }
    LaunchedEffect(draft.binding) {
        withFrameNanos { }
        labelFocus.requestFocus()
    }
    HbColumn(
        modifier.fillMaxWidth().testTag("settings-edit-form").onPreviewKeyEvent { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && isEnabled) {
                onIntent(EngineConnectionsScreenIntent.CancelEdit)
                true
            } else {
                false
            }
        },
        gap = HbTheme.spacing.m,
    ) {
        HbText(stringResource(Res.string.settings_edit_connection), style = HbTheme.typography.title)
        HbText(
            stringResource(Res.string.settings_edit_description),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbDivider()
        Spacer(Modifier.height(HbTheme.spacing.xxl + HbTheme.spacing.xxl))
        EditFieldLabel(stringResource(Res.string.wizard_field_label))
        Spacer(Modifier.height(HbTheme.spacing.xxs))
        HbTextField(
            draft.form.label,
            { onIntent(EngineConnectionsScreenIntent.EditLabel(it)) },
            Modifier.fillMaxWidth().focusRequester(labelFocus).testTag("settings-edit-label"),
            enabled = isEnabled,
            accessibleLabel = stringResource(Res.string.wizard_field_label),
        )
        if (draft.hasKey) {
            Spacer(Modifier.height(HbTheme.spacing.xxs))
            EditKeyField(state, onIntent)
            Spacer(Modifier.height(HbTheme.spacing.m))
        }
        EditFieldLabel(stringResource(Res.string.settings_edit_host))
        Spacer(Modifier.height(HbTheme.spacing.xxs))
        HbTextField(
            draft.form.origin,
            { onIntent(EngineConnectionsScreenIntent.EditOrigin(it)) },
            Modifier.fillMaxWidth().testTag("settings-edit-origin"),
            enabled = isEnabled && draft.isOriginEditable,
            accessibleLabel = stringResource(Res.string.settings_edit_host),
        )
        EditFormError(draft.formError)
        Spacer(Modifier.height(HbTheme.spacing.xxl + HbTheme.spacing.m))
        HbRow(gap = HbTheme.spacing.m) {
            HbButton(
                stringResource(if (state.isSaving) Res.string.settings_saving else Res.string.settings_save),
                { onIntent(EngineConnectionsScreenIntent.SaveEdit) },
                Modifier.testTag("settings-edit-save"),
                enabled = isEnabled && !state.isEditFailure && draft.form.label.isNotBlank(),
            )
            HbButton(
                stringResource(Res.string.conn_cancel),
                { onIntent(EngineConnectionsScreenIntent.CancelEdit) },
                Modifier.testTag("settings-edit-cancel"),
                style = HbButtonStyle.Secondary,
                enabled = isEnabled,
            )
        }
        EditFailure(state)
    }
}

@Composable
private fun EditFormError(error: FormError?) {
    if (error != null) {
        HbBanner(
            stringResource(
                when (error) {
                    FormError.InvalidOrigin -> Res.string.wizard_error_invalid_host
                    FormError.InsecureOrigin -> Res.string.wizard_error_insecure_host
                    FormError.MissingKey -> Res.string.wizard_error_missing_key
                },
            ),
        )
    }
}

@Composable
private fun EditFailure(state: EngineConnectionsScreenState) {
    state.failure?.let { failure ->
        if (state.isEditFailure) {
            HbBanner(
                stringResource(
                    if (failure == FailureUi.ConnectionChanged) {
                        Res.string.settings_edit_conflict
                    } else {
                        Res.string.settings_edit_failed
                    },
                ),
            )
        } else {
            FailurePanel(failure)
        }
    }
}

@Composable
private fun EditKeyField(state: EngineConnectionsScreenState, onIntent: (EngineConnectionsScreenIntent) -> Unit) {
    val draft = state.editDraft ?: return
    var isVisible by remember(draft.binding) { mutableStateOf(false) }
    HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.m) {
        EditFieldLabel(stringResource(Res.string.wizard_field_key))
        Spacer(Modifier.height(HbTheme.spacing.xxs))
        HbTextField(
            draft.form.key.value,
            { onIntent(EngineConnectionsScreenIntent.EditKey(it)) },
            Modifier.fillMaxWidth().testTag("settings-edit-key"),
            placeholder = stringResource(Res.string.settings_edit_key_placeholder),
            enabled = !state.isSaving,
            accessibleLabel = stringResource(Res.string.wizard_field_key),
            isSecret = true,
            isSecretVisible = isVisible,
            trailingContent = {
                HbIconButton(
                    if (isVisible) HbIcons.EyeOff else HbIcons.Eye,
                    stringResource(if (isVisible) Res.string.wizard_hide_key else Res.string.wizard_show_key),
                    { isVisible = !isVisible },
                    enabled = !state.isSaving,
                    size = HbTheme.dimensions.controlHeight,
                )
            },
        )
        HbText(
            stringResource(Res.string.settings_edit_key_hint),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun EditFieldLabel(text: String) {
    HbText(text, style = HbTheme.typography.label, color = HbTheme.colors.textPrimary)
}
