package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbComposerToggle
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.planning_chip
import io.aequicor.heartbeat.feature.aistudio.impl.resources.planning_mode
import org.jetbrains.compose.resources.stringResource
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/**
 * Whether the pane is in planning mode, when the "+" menu offers to switch it: a new chat before its first message,
 * or an ordinary idle chat. Organism and helper chats keep their turns to their owners; null hides the item.
 */
internal fun PaneContent.planningSelection(): Boolean? {
    val session = session
    return when {
        !planning.isEnabled || pane.isCreating -> null
        pane.sessionId == null -> pane.isPlanning.takeIf { !pane.isOrganism }
        session == null || session.isRunning || session.isHelper || session.isOrganism -> null
        else -> planning.plan?.isPlanning == true
    }
}

/** The command that switches planning mode of the pane to [isEnabled]. */
internal fun PaneContent.planningIntent(isEnabled: Boolean): StudioIntent.Plan? {
    val sessionId = pane.sessionId
    return if (sessionId == null) {
        StudioIntent.SelectPlanning(pane.id, isEnabled)
    } else {
        StudioIntent.SetPlanning(sessionId, isEnabled)
    }
}

/**
 * The planning chip of the composer: the mode chosen for a new chat, or the plan of the chat with its revision;
 * switching it off leaves planning, and a chat implementing its plan can plan it again.
 */
@Composable
internal fun PlanningModeToggle(content: PaneContent, onIntent: (StudioIntent) -> Unit) {
    val pane = content.pane
    val plan = content.planning.plan
    val label = when {
        pane.sessionId == null && pane.isPlanning -> stringResource(Res.string.planning_mode)
        pane.sessionId != null && plan != null -> stringResource(Res.string.planning_chip, plan.revision)
        else -> return
    }
    HbComposerToggle(
        label = label,
        isChecked = pane.sessionId == null || plan?.isPlanning == true,
        onCheckedChange = { isEnabled -> content.planningIntent(isEnabled)?.let(onIntent) },
        enabled = content.planningSelection() != null,
        icon = HbIcons.Plan,
        modifier = Modifier.testTag("planning-mode-${pane.id}"),
    )
}
