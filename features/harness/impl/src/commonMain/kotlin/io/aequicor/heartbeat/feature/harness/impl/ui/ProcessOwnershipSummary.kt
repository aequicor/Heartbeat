package io.aequicor.heartbeat.feature.harness.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.feature.harness.impl.presentation.OwnershipActionUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.OwnershipTaskKindUi
import io.aequicor.heartbeat.feature.harness.impl.presentation.ProcessOwnershipUi
import io.aequicor.heartbeat.feature.harness.impl.resources.Res
import io.aequicor.heartbeat.feature.harness.impl.resources.process_ownership_cancel
import io.aequicor.heartbeat.feature.harness.impl.resources.process_ownership_claim
import io.aequicor.heartbeat.feature.harness.impl.resources.process_ownership_enroll
import io.aequicor.heartbeat.feature.harness.impl.resources.process_ownership_issue
import io.aequicor.heartbeat.feature.harness.impl.resources.process_ownership_none
import io.aequicor.heartbeat.feature.harness.impl.resources.process_ownership_prepare
import io.aequicor.heartbeat.feature.harness.impl.resources.process_ownership_pull
import io.aequicor.heartbeat.feature.harness.impl.resources.process_ownership_summary
import io.aequicor.heartbeat.feature.harness.impl.resources.process_ownership_task
import org.jetbrains.compose.resources.stringResource

/** Complete ownership decision details wrap on compact native surfaces; hashes and hosts remain untruncated. */
@Composable
internal fun ProcessOwnershipSummary(ownership: ProcessOwnershipUi, modifier: Modifier = Modifier) {
    val none = stringResource(Res.string.process_ownership_none)
    HbText(
        stringResource(
            Res.string.process_ownership_summary,
            stringResource(ownership.action.label()),
            ownership.source ?: none,
            ownership.recipient,
            ownership.epoch?.toString() ?: none,
            ownership.checkpoint ?: none,
            ownership.definition,
            ownership.branch,
            stringResource(
                Res.string.process_ownership_task,
                ownership.repository,
                stringResource(ownership.taskKind.label()),
                ownership.taskNumber,
            ),
        ),
        modifier,
    )
}

private fun OwnershipActionUi.label() = when (this) {
    OwnershipActionUi.Enroll -> Res.string.process_ownership_enroll
    OwnershipActionUi.Prepare -> Res.string.process_ownership_prepare
    OwnershipActionUi.Claim -> Res.string.process_ownership_claim
    OwnershipActionUi.Cancel -> Res.string.process_ownership_cancel
}

private fun OwnershipTaskKindUi.label() = when (this) {
    OwnershipTaskKindUi.Issue -> Res.string.process_ownership_issue
    OwnershipTaskKindUi.Pull -> Res.string.process_ownership_pull
}
