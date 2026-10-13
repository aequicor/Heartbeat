package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_checklist_ready
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_checklist_waiting
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SessionUi.checklistStatusLabel(): String? = when {
    isRunning -> null
    isAwaitingChecklist -> stringResource(Res.string.session_checklist_waiting)
    isReady -> stringResource(Res.string.session_checklist_ready)
    else -> null
}
