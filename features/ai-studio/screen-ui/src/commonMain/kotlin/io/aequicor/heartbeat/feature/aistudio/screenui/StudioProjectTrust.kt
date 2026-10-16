package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_trust_content
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_trust_message
import io.aequicor.heartbeat.feature.aistudio.impl.resources.project_trust_trusted
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProjectTrustNoticeUi
import org.jetbrains.compose.resources.stringResource

/**
 * Asks whether the focused chat's project may load what it configures for its engine (settings, tool servers,
 * extensions) or only its instructions; the choice stays until changed in the engine control panel.
 */
@Composable
internal fun ProjectTrustNotice(
    notice: ProjectTrustNoticeUi,
    onIntent: (AiStudioScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val decide = { isTrusted: Boolean ->
        onIntent(AiStudioScreenIntent.DecideProjectTrust(notice.projectId, notice.engine, isTrusted))
    }
    HbBanner(
        stringResource(Res.string.project_trust_message, notice.projectTitle, notice.engineTitle),
        modifier.testTag("studio-project-trust"),
        HbTone.Warning,
    ) {
        HbButton(
            stringResource(Res.string.project_trust_content),
            { decide(false) },
            Modifier.testTag("studio-project-trust-content"),
            HbButtonStyle.Ghost,
            size = HbButtonSize.Small,
        )
        HbButton(
            stringResource(Res.string.project_trust_trusted),
            { decide(true) },
            Modifier.testTag("studio-project-trust-trusted"),
            HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
        )
    }
}
