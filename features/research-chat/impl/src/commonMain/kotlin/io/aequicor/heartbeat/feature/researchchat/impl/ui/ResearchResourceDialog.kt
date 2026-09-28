package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResourceKindUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResourceScopeUi
import io.aequicor.heartbeat.feature.researchchat.impl.resources.Res
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_add
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_add_source
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_cancel
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_document_text
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_error
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_file_attached
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_first_question_hint
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_image_model_hint
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_image_url
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_import_file
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_pdf_model_hint
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_scope_question
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_scope_session
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_source_name
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_website_url
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ResearchResourceDialog(state: ResearchScreenState, onIntent: (ResearchScreenIntent) -> Unit) {
    Dialog(onDismissRequest = { onIntent(ResearchScreenIntent.ShowResourceDialog(false)) }) {
        HbPanel(
            Modifier.widthIn(max = HbTheme.dimensions.chatMessageMaxWidth).testTag("research-resource-dialog"),
            background = HbTheme.colors.surface,
        ) {
            HbColumn(
                Modifier.hbVerticalScroll(rememberScrollState()).padding(HbTheme.spacing.xl),
                gap = HbTheme.spacing.l,
            ) {
                HbText(stringResource(Res.string.research_add_source), style = HbTheme.typography.title)
                ResourceKindTabs(state.resourceKind, onIntent)
                ResourceForm(state, onIntent)
                if (state.hasError) {
                    HbText(
                        stringResource(Res.string.research_error),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("research-source-error"),
                        color = HbTheme.colors.error,
                    )
                }
                if (state.questions.firstOrNull()?.isSelected == true || state.questions.isEmpty()) {
                    HbText(stringResource(Res.string.research_scope_session))
                } else {
                    ResourceScopeTabs(state.resourceScope, onIntent)
                }
                HbText(
                    stringResource(Res.string.research_first_question_hint),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
                ResourceFormActions(state, onIntent)
            }
        }
    }
}

@Composable
private fun ResourceFormActions(state: ResearchScreenState, onIntent: (ResearchScreenIntent) -> Unit) {
    HbFlowRow(Modifier.fillMaxWidth()) {
        if (state.isFileImportAvailable) {
            HbButton(
                stringResource(Res.string.research_import_file),
                { onIntent(ResearchScreenIntent.ImportFile) },
                Modifier.testTag("research-import-file"),
                style = HbButtonStyle.Secondary,
                enabled = state.isEditable,
            )
        }
        HbButton(
            stringResource(Res.string.research_cancel),
            { onIntent(ResearchScreenIntent.ShowResourceDialog(false)) },
            style = HbButtonStyle.Quiet,
        )
        HbButton(
            stringResource(Res.string.research_add),
            { onIntent(ResearchScreenIntent.AddResource) },
            Modifier.testTag("research-confirm-source"),
            enabled = state.isEditable && state.resourceValue.isNotBlank(),
        )
    }
}

@Composable
private fun ResourceForm(state: ResearchScreenState, onIntent: (ResearchScreenIntent) -> Unit) {
    HbColumn(Modifier.fillMaxWidth()) {
        HbTextField(
            value = state.resourceTitle,
            onValueChange = { onIntent(ResearchScreenIntent.ResourceTitleChanged(it)) },
            modifier = Modifier.fillMaxWidth().testTag("research-source-title"),
            placeholder = stringResource(Res.string.research_source_name),
            enabled = state.isEditable,
        )
        if (state.resourceMediaType != null) {
            HbText(stringResource(Res.string.research_file_attached), Modifier.testTag("research-file-attached"))
        } else {
            val label = stringResource(
                when (state.resourceKind) {
                    ResourceKindUi.Website -> Res.string.research_website_url
                    ResourceKindUi.Document -> Res.string.research_document_text
                    ResourceKindUi.Image -> Res.string.research_image_url
                },
            )
            HbTextField(
                value = state.resourceValue,
                onValueChange = { onIntent(ResearchScreenIntent.ResourceValueChanged(it)) },
                modifier = Modifier.fillMaxWidth().heightIn(max = HbTheme.dimensions.toolPayloadMaxHeight)
                    .testTag("research-source-value"),
                placeholder = label,
                enabled = state.isEditable,
                singleLine = state.resourceKind != ResourceKindUi.Document,
            )
        }
        val modelHint = when {
            state.resourceKind == ResourceKindUi.Image -> Res.string.research_image_model_hint
            state.resourceMediaType == "application/pdf" -> Res.string.research_pdf_model_hint
            else -> null
        }
        if (modelHint != null) {
            HbText(
                stringResource(modelHint),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun ResourceKindTabs(kind: ResourceKindUi, onIntent: (ResearchScreenIntent) -> Unit) {
    HbRow(Modifier.fillMaxWidth().selectableGroup(), gap = HbTheme.spacing.xs) {
        ResourceKindUi.entries.forEach { entry ->
            HbNavigationItem(
                label = entry.label(),
                onClick = { onIntent(ResearchScreenIntent.ResourceKindChanged(entry)) },
                modifier = Modifier.weight(1f).testTag("research-kind-${entry.name}"),
                isSelected = kind == entry,
                role = Role.Tab,
            )
        }
    }
}

@Composable
private fun ResourceScopeTabs(scope: ResourceScopeUi, onIntent: (ResearchScreenIntent) -> Unit) {
    HbRow(Modifier.fillMaxWidth().selectableGroup(), gap = HbTheme.spacing.xs) {
        ResourceScopeUi.entries.forEach { entry ->
            HbNavigationItem(
                label = stringResource(
                    if (entry == ResourceScopeUi.Session) {
                        Res.string.research_scope_session
                    } else {
                        Res.string.research_scope_question
                    },
                ),
                onClick = { onIntent(ResearchScreenIntent.ResourceScopeChanged(entry)) },
                modifier = Modifier.weight(1f).testTag("research-scope-${entry.name}"),
                isSelected = scope == entry,
                role = Role.Tab,
            )
        }
    }
}
