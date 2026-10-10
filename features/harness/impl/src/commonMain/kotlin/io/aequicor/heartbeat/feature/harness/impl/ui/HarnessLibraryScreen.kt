package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.harness.impl.presentation.ApprovalUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessLibraryAction
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessLibraryIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessLibraryModel
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessLibraryState
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessRowUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.PhaseUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ScopeUi
import io.aequicor.heartbeat.feature.harness.impl.resources.Res
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_approval_accept_all
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_approval_accept_all_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_approval_ask
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_approval_ask_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_approval_by_trust
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_approval_by_trust_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_approval_section
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_approval_section_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_approval_warning
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_dismiss
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_empty
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_empty_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_failed_badge
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_items
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_list_section
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_no_code
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_save_failed
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_scope_attached
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_scope_profile
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_scope_projects
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_title
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_use
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** The harness library: approval of agent edits and the harnesses of the profile. */
@Composable
internal fun HarnessLibraryScreen(
    model: HarnessLibraryModel,
    onOpen: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val open by rememberUpdatedState(onOpen)
    val state by produceState(HarnessLibraryState(), model) {
        model.store.collect {
            launch { actions.collect { action -> if (action is HarnessLibraryAction.OpenDetail) open(action.id) } }
            states.collect { value = it }
        }
    }
    HarnessLibraryContent(state, model.store::intent, onBack, modifier)
}

/** Stateless library screen. */
@Composable
internal fun HarnessLibraryContent(
    state: HarnessLibraryState,
    onIntent: (HarnessLibraryIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HarnessPane(stringResource(Res.string.harness_title), "harness-library", onBack, modifier) {
        if (state.isSaveFailed) {
            item(key = "error") {
                HbBanner(stringResource(Res.string.harness_save_failed), Modifier.testTag("harness-library-error")) {
                    HbButton(
                        stringResource(Res.string.harness_dismiss),
                        { onIntent(HarnessLibraryIntent.DismissError) },
                        style = HbButtonStyle.Secondary,
                        size = HbButtonSize.Small,
                    )
                }
            }
        }
        if (!phaseItems(state.phase) { onIntent(HarnessLibraryIntent.Reload) }) return@HarnessPane
        item(key = "approval") { ApprovalSection(state.approval, onIntent) }
        if (!state.isCodeSupported) {
            item(key = "no-code") {
                HbBanner(stringResource(Res.string.harness_no_code), tone = HbTone.Neutral)
            }
        }
        if (state.harnesses.isEmpty()) {
            item(key = "empty") {
                HbEmptyState(
                    stringResource(Res.string.harness_empty),
                    Modifier.testTag("harness-library-empty"),
                    description = stringResource(Res.string.harness_empty_hint),
                )
            }
        } else {
            item(key = "list") { HarnessList(state, onIntent) }
        }
    }
}

@Composable
private fun ApprovalSection(
    approval: ApprovalUi,
    onIntent: (HarnessLibraryIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.harness_approval_section),
        modifier.selectableGroup().testTag("harness-approval"),
        description = stringResource(Res.string.harness_approval_section_hint),
    ) {
        ApprovalUi.entries.forEachIndexed { index, level ->
            if (index > 0) HbDivider()
            val isSelected = level == approval
            HbSettingsRow(
                stringResource(level.title()),
                Modifier.fillMaxWidth().semantics { selected = isSelected }.testTag("harness-approval-${level.name}"),
                description = stringResource(level.hint()),
                onClick = { onIntent(HarnessLibraryIntent.SelectApproval(level)) },
                isSelected = isSelected,
                role = Role.RadioButton,
            ) {
                if (isSelected) HbIcon(HbIcons.Check, contentDescription = null)
            }
        }
        if (approval == ApprovalUi.AcceptAll) {
            HbBanner(
                stringResource(Res.string.harness_approval_warning),
                Modifier.testTag("harness-approval-warning"),
                tone = HbTone.Warning,
            )
        }
    }
}

@Composable
private fun HarnessList(
    state: HarnessLibraryState,
    onIntent: (HarnessLibraryIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(stringResource(Res.string.harness_list_section), modifier.testTag("harness-list")) {
        state.harnesses.forEachIndexed { index, row ->
            key(row.id) {
                if (index > 0) HbDivider()
                HarnessRow(row, onIntent)
            }
        }
    }
}

@Composable
private fun HarnessRow(row: HarnessRowUi, onIntent: (HarnessLibraryIntent) -> Unit, modifier: Modifier = Modifier) {
    val items = pluralStringResource(Res.plurals.harness_items, row.itemCount, row.itemCount)
    HbSettingsRow(
        row.title,
        modifier.fillMaxWidth().testTag("harness-row-${row.id}"),
        description = listOf(row.name, row.scopeLabel(), items).joinToString(" · "),
        onClick = { onIntent(HarnessLibraryIntent.Open(row.id)) },
    ) {
        HbRow(gap = HbTheme.spacing.s) {
            if (row.hasFailures) HbBadge(stringResource(Res.string.harness_failed_badge), tone = HbTone.Danger)
            HbSwitch(
                row.isEnabled,
                { onIntent(HarnessLibraryIntent.SetEnabled(row.id, it)) },
                stringResource(Res.string.harness_use, row.title),
                Modifier.testTag("harness-enabled-${row.id}"),
            )
        }
    }
}

@Composable
internal fun HarnessRowUi.scopeLabel(): String = when (scope) {
    ScopeUi.Attached -> stringResource(Res.string.harness_scope_attached)
    ScopeUi.Profile -> stringResource(Res.string.harness_scope_profile)
    ScopeUi.Projects -> pluralStringResource(Res.plurals.harness_scope_projects, projectCount, projectCount)
}

private fun ApprovalUi.title(): StringResource = when (this) {
    ApprovalUi.Ask -> Res.string.harness_approval_ask
    ApprovalUi.ByTrust -> Res.string.harness_approval_by_trust
    ApprovalUi.AcceptAll -> Res.string.harness_approval_accept_all
}

private fun ApprovalUi.hint(): StringResource = when (this) {
    ApprovalUi.Ask -> Res.string.harness_approval_ask_hint
    ApprovalUi.ByTrust -> Res.string.harness_approval_by_trust_hint
    ApprovalUi.AcceptAll -> Res.string.harness_approval_accept_all_hint
}

private val previewState = HarnessLibraryState(
    phase = PhaseUi.Ready,
    approval = ApprovalUi.ByTrust,
    harnesses = persistentListOf(
        HarnessRowUi("1", "compose_ui", "Compose UI", ScopeUi.Projects, 1, 6, isEnabled = true, hasFailures = false),
        HarnessRowUi("2", "release", "Release", ScopeUi.Attached, 0, 2, isEnabled = false, hasFailures = true),
    ),
    isCodeSupported = true,
)

@Preview
@Composable
private fun HarnessLibraryLightPreview() {
    HbTheme(darkTheme = false) { HarnessLibraryContent(previewState, {}, null) }
}

@Preview
@Composable
private fun HarnessLibraryDarkPreview() {
    HbTheme(darkTheme = true) { HarnessLibraryContent(previewState.copy(approval = ApprovalUi.AcceptAll), {}, {}) }
}

@Preview
@Composable
private fun HarnessLibraryEmptyPreview() {
    HbTheme(darkTheme = false) {
        HarnessLibraryContent(HarnessLibraryState(phase = PhaseUi.Ready, isCodeSupported = false), {}, null)
    }
}
