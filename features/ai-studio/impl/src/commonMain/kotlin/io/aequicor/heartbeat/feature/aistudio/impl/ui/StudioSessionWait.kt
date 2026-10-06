package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionWaitUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_sleeping
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_waiting_event
import org.jetbrains.compose.resources.stringResource

/** A scheduled wait never marks the engine busy or enables its stop control. */
@Composable
internal fun SessionWaitStatus(session: SessionUi, modifier: Modifier = Modifier) {
    val label = session.waitStatusLabel() ?: return
    HbRow(
        modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("session-wait-status"),
        gap = HbTheme.spacing.s,
    ) {
        HbIcon(session.waitIcon(), null, tint = HbTheme.colors.textSecondary)
        HbText(label, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
    }
}

@Composable
internal fun SessionUi.waitStatusLabel(): String? = when {
    isRunning -> null
    scheduledWait == SessionWaitUi.Sleeping -> stringResource(Res.string.session_sleeping)
    scheduledWait == SessionWaitUi.WaitingForEvent -> stringResource(Res.string.session_waiting_event)
    else -> checklistStatusLabel()
}

internal fun SessionUi.waitIcon() = if (scheduledWait == SessionWaitUi.Sleeping) HbIcons.Moon else HbIcons.Chat
