package io.aequicor.heartbeat.chromiumplugin.panel.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.chromiumplugin.panel.resources.Res
import io.aequicor.heartbeat.chromiumplugin.panel.resources.agent_consent
import io.aequicor.heartbeat.chromiumplugin.panel.resources.agent_consent_hint
import io.aequicor.heartbeat.chromiumplugin.panel.resources.agent_controlling
import io.aequicor.heartbeat.chromiumplugin.panel.resources.revoke_all
import io.aequicor.heartbeat.chromiumplugin.panel.resources.status_connecting
import io.aequicor.heartbeat.chromiumplugin.panel.resources.status_disconnected
import io.aequicor.heartbeat.chromiumplugin.panel.resources.status_forbidden
import io.aequicor.heartbeat.chromiumplugin.panel.resources.status_host_missing
import io.aequicor.heartbeat.chromiumplugin.panel.resources.status_incompatible
import io.aequicor.heartbeat.chromiumplugin.panel.resources.status_waiting
import io.aequicor.heartbeat.chromiumplugin.panel.resources.studio_off
import io.aequicor.heartbeat.chromiumplugin.panel.store.AgentControl
import io.aequicor.heartbeat.chromiumplugin.panel.store.PanelNotice
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Why the studio is not usable right now; connecting is announced quietly. */
@Composable
internal fun PanelBanner(notice: PanelNotice?, modifier: Modifier = Modifier) {
    if (notice == null) return
    HbBanner(
        stringResource(notice.text),
        modifier.fillMaxWidth().padding(HbTheme.spacing.s),
        tone = if (notice.isProblem) HbTone.Warning else HbTone.Neutral,
    )
}

/**
 * Consent to agent control in this browser ([isConsentShown]: on the chat list, while the app offers it) and,
 * on every screen, the tabs agents drive with a way to take them back.
 */
@Composable
internal fun AgentControlSection(
    agent: AgentControl,
    isConsentShown: Boolean,
    onConsent: (Boolean) -> Unit,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isConsentShown && agent.controlled == 0) return
    HbColumn(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.s), gap = HbTheme.spacing.none) {
        if (isConsentShown) {
            val consent = stringResource(Res.string.agent_consent)
            HbSettingsRow(consent, description = stringResource(Res.string.agent_consent_hint)) {
                HbSwitch(agent.isAllowed, onConsent, consent)
            }
        }
        if (agent.controlled > 0) {
            HbRow(Modifier.fillMaxWidth().padding(HbTheme.spacing.s), gap = HbTheme.spacing.s) {
                HbText(stringResource(Res.string.agent_controlling, agent.controlled), Modifier.weight(1f))
                HbButton(
                    stringResource(Res.string.revoke_all),
                    onRevoke,
                    style = HbButtonStyle.Secondary,
                    size = HbButtonSize.Small,
                )
            }
        }
    }
}

private val PanelNotice.text: StringResource
    get() = when (this) {
        PanelNotice.Connecting -> Res.string.status_connecting
        PanelNotice.HostMissing -> Res.string.status_host_missing
        PanelNotice.Forbidden -> Res.string.status_forbidden
        PanelNotice.WaitingForApp -> Res.string.status_waiting
        PanelNotice.Incompatible -> Res.string.status_incompatible
        PanelNotice.Disconnected -> Res.string.status_disconnected
        PanelNotice.StudioOff -> Res.string.studio_off
    }
