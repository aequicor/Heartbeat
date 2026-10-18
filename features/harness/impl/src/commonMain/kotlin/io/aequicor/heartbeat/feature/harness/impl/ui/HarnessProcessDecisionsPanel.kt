package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessProcessDecisionsModel
import io.aequicor.heartbeat.feature.harness.impl.presentation.ProcessDecisionStatusUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ProcessDecisionUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ProcessDecisionsUiIntent
import io.aequicor.heartbeat.feature.harness.impl.presentation.ProcessDecisionsUiState
import io.aequicor.heartbeat.feature.harness.impl.resources.Res
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_retry
import io.aequicor.heartbeat.feature.harness.impl.resources.harness_save_failed
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_allow
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_approved
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_completed
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_context
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_deadline
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_denied
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_deny
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_executing
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_hint
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_pending
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_process
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_revoke
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_revoked
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_subject
import io.aequicor.heartbeat.feature.harness.impl.resources.process_decisions_title
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Native host controls. This renderer never registers a script method or web bridge. */
@Composable
internal fun HarnessProcessDecisionsPanel(model: HarnessProcessDecisionsModel, modifier: Modifier = Modifier) {
    val state by produceState(ProcessDecisionsUiState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    ProcessDecisionsContent(state, model.store::intent, modifier)
}

/** Exact command words remain fully visible, wrapping rather than truncating arguments on compact screens. */
@Composable
internal fun ProcessDecisionsContent(
    state: ProcessDecisionsUiState,
    onIntent: (ProcessDecisionsUiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.rows.isEmpty() && !state.isFailed) return
    HbSettingsSection(
        stringResource(Res.string.process_decisions_title),
        modifier.testTag("process-decisions"),
        description = stringResource(Res.string.process_decisions_hint),
    ) {
        if (state.isFailed) {
            HbBanner(stringResource(Res.string.harness_save_failed)) {
                HbButton(stringResource(Res.string.harness_retry), { onIntent(ProcessDecisionsUiIntent.Reload) })
            }
        }
        state.rows.forEach { row ->
            HbText(stringResource(row.status.label()))
            HbText(stringResource(Res.string.process_decisions_context, row.repository, row.workspace, row.operation))
            HbText(stringResource(Res.string.process_decisions_subject, row.subject))
            val process = stringResource(
                Res.string.process_decisions_process,
                row.process,
                row.stage,
                row.attempt,
                row.inputRevision,
            )
            HbText(process)
            HbText(stringResource(Res.string.process_decisions_deadline, row.expiresAt, row.timeoutMillis))
            HbText(row.words)
            DecisionControls(row, state.isBusy, onIntent)
            HbDivider()
        }
    }
}

@Composable
private fun DecisionControls(
    row: ProcessDecisionUi,
    isBusy: Boolean,
    onIntent: (ProcessDecisionsUiIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbFlowRow(modifier) {
        if (row.status == ProcessDecisionStatusUi.Pending) {
            HbButton(
                stringResource(Res.string.process_decisions_allow),
                { onIntent(ProcessDecisionsUiIntent.Answer(row.id, row.revision, true)) },
                modifier = Modifier.testTag("process-decision-allow-${row.id}"),
                enabled = !isBusy,
                size = HbButtonSize.Small,
            )
            HbButton(
                stringResource(Res.string.process_decisions_deny),
                { onIntent(ProcessDecisionsUiIntent.Answer(row.id, row.revision, false)) },
                modifier = Modifier.testTag("process-decision-deny-${row.id}"),
                enabled = !isBusy,
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }
        if (row.status == ProcessDecisionStatusUi.Approved) {
            HbButton(
                stringResource(Res.string.process_decisions_revoke),
                { onIntent(ProcessDecisionsUiIntent.Revoke(row.id, row.revision)) },
                modifier = Modifier.testTag("process-decision-revoke-${row.id}"),
                enabled = !isBusy,
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        }
    }
}

private fun ProcessDecisionStatusUi.label() = when (this) {
    ProcessDecisionStatusUi.Pending -> Res.string.process_decisions_pending
    ProcessDecisionStatusUi.Approved -> Res.string.process_decisions_approved
    ProcessDecisionStatusUi.Denied -> Res.string.process_decisions_denied
    ProcessDecisionStatusUi.Revoked -> Res.string.process_decisions_revoked
    ProcessDecisionStatusUi.Executing -> Res.string.process_decisions_executing
    ProcessDecisionStatusUi.Completed -> Res.string.process_decisions_completed
}
