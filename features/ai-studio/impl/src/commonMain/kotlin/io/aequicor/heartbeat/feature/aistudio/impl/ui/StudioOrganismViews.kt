package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbComposerToggle
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenu
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.OrganismActionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.OrganismStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.OrganismUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SubSessionKindUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SubSessionStateUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SubSessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_abort
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_complaint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_dispute
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_permission
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_resume
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_answered
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_awaiting
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_completed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_died
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_germinating
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_judging
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_killed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_resting
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_sentenced
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_spared
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_stalled
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_undecided
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_state_working
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_status_aborted
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_status_completed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_status_developing
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_status_stalled
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_sub_sessions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.organism_zygote
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** An organism chat's feed follows the shown sub-session, so switching resets its timeline. */
internal fun PaneContent.feedId(): String? = pane.sessionId?.let { if (organism != null) "$it/$subSession" else it }

/** The organism mode is offered in the "+" menu of a new chat only, before its first message. */
internal fun PaneContent.isOrganismOffered(): Boolean = isOrganismEnabled && pane.sessionId == null && !pane.isCreating

/** The chosen mode of a new chat, which can be switched off here; an organism chat shows it pinned. */
@Composable
internal fun OrganismModeToggle(content: PaneContent, onIntent: (AiStudioScreenIntent) -> Unit) {
    val pane = content.pane
    if (content.session?.isOrganism == true) {
        HbComposerToggle(
            label = stringResource(Res.string.organism_mode),
            isChecked = true,
            onCheckedChange = {},
            enabled = false,
            icon = HbIcons.Users,
            modifier = Modifier.testTag("organism-pinned-${pane.id}"),
        )
    } else if (pane.sessionId == null && pane.isOrganism) {
        HbComposerToggle(
            label = stringResource(Res.string.organism_mode),
            isChecked = true,
            onCheckedChange = { onIntent(AiStudioScreenIntent.SelectOrganism(pane.id, it)) },
            icon = HbIcons.Users,
            modifier = Modifier.testTag("organism-mode-${pane.id}"),
        )
    }
}

/**
 * The sub-sessions of an organism chat in the top-right of its pane: the zygote, the divided cells and the judges,
 * each with its state. Choosing one shows its transcript; the menu also stops the organism or resumes its zygote.
 */
@Composable
internal fun OrganismSwitcher(content: PaneContent, onIntent: (AiStudioScreenIntent) -> Unit) {
    val organism = content.organism ?: return
    val sessionId = content.session?.id ?: return
    var isExpanded by remember { mutableStateOf(false) }
    val current = organism.subSessions.firstOrNull { it.key == content.subSession }
    val items = organism.subSessions.map { sub ->
        HbMenuItem(
            id = SUB_SESSION_PREFIX + sub.key,
            label = subSessionLabel(sub),
            shortcut = stringResource(sub.state.label()),
            isEnabled = sub.isViewable,
            isChecked = sub.key == content.subSession,
        )
    } + organismActions(organism)
    Box {
        HbButton(
            current?.let { subSessionLabel(it) } ?: stringResource(Res.string.organism_sub_sessions),
            { isExpanded = true },
            Modifier.widthIn(max = HbTheme.dimensions.composerMenuMaxWidth)
                .testTag("organism-switcher-${content.pane.id}"),
            style = HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
        )
        HbMenu(
            items.toImmutableList(),
            isExpanded,
            { isExpanded = false },
            { id ->
                isExpanded = false
                val intent = when (id) {
                    ABORT_ACTION -> AiStudioScreenIntent.ControlOrganism(sessionId, OrganismActionUi.Abort)
                    RESUME_ACTION -> AiStudioScreenIntent.ControlOrganism(sessionId, OrganismActionUi.Resume)
                    else -> AiStudioScreenIntent.SelectSubSession(sessionId, id.removePrefix(SUB_SESSION_PREFIX))
                }
                onIntent(intent)
            },
            stringResource(Res.string.organism_sub_sessions),
        )
    }
}

@Composable
private fun organismActions(organism: OrganismUi): List<HbMenuItem> = listOfNotNull(
    HbMenuItem(RESUME_ACTION, stringResource(Res.string.organism_resume), isGroupStart = true)
        .takeIf { organism.status == OrganismStatusUi.Stalled },
    HbMenuItem(
        ABORT_ACTION,
        stringResource(Res.string.organism_abort),
        isGroupStart = organism.status != OrganismStatusUi.Stalled,
    ).takeIf { organism.status == OrganismStatusUi.Developing || organism.status == OrganismStatusUi.Stalled },
)

/**
 * The organism's state and the permission requests of its cells, answered by the user. What a request approves is
 * shown whole within [descriptionMaxHeight], as the pane's own requests are. A chat whose organism is out of view
 * (organic AI is off or asleep, or the organism is no longer kept) says so instead of showing nothing.
 */
@Composable
internal fun OrganismNotices(
    content: PaneContent,
    descriptionMaxHeight: Dp,
    onIntent: (AiStudioScreenIntent) -> Unit,
) {
    val sessionId = content.session?.id ?: return
    val organism = content.organism
    // While its organism is being conceived the chat runs; only one out of view afterwards is explained.
    if (organism == null && content.session.isRunning) return
    if (organism == null) {
        HbText(
            stringResource(Res.string.organism_unavailable),
            Modifier.padding(HbTheme.spacing.m).testTag("organism-unavailable-${content.pane.id}"),
        )
        return
    }
    HbText(
        stringResource(organism.status.label()),
        Modifier.padding(HbTheme.spacing.m).testTag("organism-status-${content.pane.id}")
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
    organism.permissions.forEach { request ->
        // Request ids come from each cell's engine, so only the cell makes them unique.
        val id = "${request.cell}-${request.requestId}"
        key(request.cell, request.requestId) {
            HbColumn(
                Modifier.padding(HbTheme.spacing.m).testTag("organism-permission-$id"),
                gap = HbTheme.spacing.s,
            ) {
                HbText(
                    stringResource(Res.string.organism_permission, request.cellName, request.title),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                request.description?.takeIf { it.isNotBlank() }?.let {
                    PermissionDescription(it, "organism-$id", descriptionMaxHeight)
                }
                request.options.forEach { option ->
                    HbButton(
                        text = option.title,
                        onClick = {
                            onIntent(
                                AiStudioScreenIntent.DecideOrganism(
                                    sessionId,
                                    request.cell,
                                    request.turn,
                                    request.requestId,
                                    option.id,
                                ),
                            )
                        },
                        modifier = Modifier.testTag("organism-permission-$id-${option.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun subSessionLabel(sub: SubSessionUi): String = when (sub.kind) {
    SubSessionKindUi.Zygote -> stringResource(Res.string.organism_zygote)
    SubSessionKindUi.Cell -> "${sub.key} · ${sub.name}"
    SubSessionKindUi.Complaint -> stringResource(Res.string.organism_complaint, sub.key, sub.subject.orEmpty())
    SubSessionKindUi.Dispute -> stringResource(Res.string.organism_dispute, sub.key)
}

private fun SubSessionStateUi.label(): StringResource = when (this) {
    SubSessionStateUi.Germinating -> Res.string.organism_state_germinating
    SubSessionStateUi.Working -> Res.string.organism_state_working
    SubSessionStateUi.AwaitingUser -> Res.string.organism_state_awaiting
    SubSessionStateUi.Resting -> Res.string.organism_state_resting
    SubSessionStateUi.Stalled -> Res.string.organism_state_stalled
    SubSessionStateUi.Completed -> Res.string.organism_state_completed
    SubSessionStateUi.Died -> Res.string.organism_state_died
    SubSessionStateUi.Killed -> Res.string.organism_state_killed
    SubSessionStateUi.Judging -> Res.string.organism_state_judging
    SubSessionStateUi.Sentenced -> Res.string.organism_state_sentenced
    SubSessionStateUi.Spared -> Res.string.organism_state_spared
    SubSessionStateUi.Answered -> Res.string.organism_state_answered
    SubSessionStateUi.Undecided -> Res.string.organism_state_undecided
}

private fun OrganismStatusUi.label(): StringResource = when (this) {
    OrganismStatusUi.Developing -> Res.string.organism_status_developing
    OrganismStatusUi.Stalled -> Res.string.organism_status_stalled
    OrganismStatusUi.Completed -> Res.string.organism_status_completed
    OrganismStatusUi.Aborted -> Res.string.organism_status_aborted
}

private const val SUB_SESSION_PREFIX = "sub:"
private const val ABORT_ACTION = "organism:abort"
private const val RESUME_ACTION = "organism:resume"
