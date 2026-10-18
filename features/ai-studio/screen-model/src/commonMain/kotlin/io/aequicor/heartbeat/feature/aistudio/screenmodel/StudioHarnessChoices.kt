package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet

/** Toggles [harness], freezing [initialSelection] on the first edit; an empty override keeps defaults off. */
fun HarnessChoicesUi.choosePane(
    paneId: Int,
    harness: String,
    isSelected: Boolean,
    initialSelection: Set<String> = emptySet(),
): HarnessChoicesUi {
    val next = (panes[paneId] ?: initialSelection).let { if (isSelected) it + harness else it - harness }
    return copy(panes = (panes + (paneId to next.toImmutableSet())).toImmutableMap())
}

/**
 * A rejected new chat's choice returns to its pane when its draft does ([draftKey] is the pane's), joined with
 * anything chosen there meanwhile.
 */
fun HarnessChoicesUi.restorePane(submitted: SubmissionUi, draftKey: String): HarnessChoicesUi {
    if (draftKey != "pane:${submitted.paneId}") return this
    val restored = panes[submitted.paneId].orEmpty() + submitted.harnesses
    return copy(panes = (panes + (submitted.paneId to restored.toImmutableSet())).toImmutableMap())
}

/** The pane's choice left with its submission. */
fun HarnessChoicesUi.withoutPane(paneId: Int): HarnessChoicesUi =
    if (paneId in panes) copy(panes = (panes - paneId).toImmutableMap()) else this

/** Shared harnesses plus this project's choices; connected chat harnesses remain reachable for disconnection. */
fun AiStudioScreenState.availableHarnesses(pane: PaneUi): List<HarnessOptionUi> {
    val projectId = pane.projectId ?: sessions.firstOrNull { it.id == pane.sessionId }?.projectId
    val attached = pane.sessionId?.let { harnessChoices.chats[it] }.orEmpty()
    return harnessChoices.options.filter { it.isAvailableFor(projectId) || it.id in attached }
}

/**
 * Existing chats use only committed connections. Untouched new panes follow available defaults, including a library
 * loaded after the pane opened. The first manual edit freezes the complete selection, including no harnesses.
 * Organisms create their sessions outside the studio turn host and therefore do not receive these defaults; they
 * carry only a selection made by hand, which reaches every working cell of the organism.
 */
fun AiStudioScreenState.selectedHarnesses(pane: PaneUi): Set<String> {
    if (pane.sessionId != null) return harnessChoices.chats[pane.sessionId].orEmpty()
    harnessChoices.panes[pane.id]?.let { return it }
    return availableHarnesses(pane).filter { it.isDefaultForNewSessions && !pane.isOrganism }
        .mapTo(mutableSetOf()) { it.id }
}

/** Only ordinary new chats must know the library's defaults before native tool declarations are frozen. */
fun AiStudioScreenState.isHarnessSelectionReady(pane: PaneUi): Boolean =
    pane.sessionId != null || pane.isOrganism || harnessChoices.isReady

/** A project switch or library update must not leave invisible selections in a new session's first prompt. */
fun AiStudioScreenState.pruneHarnessChoices(): AiStudioScreenState {
    val choices = harnessChoices.panes.mapNotNull { (id, selected) ->
        val pane = panes.firstOrNull { it.id == id && it.sessionId == null } ?: return@mapNotNull null
        val available = availableHarnesses(pane).mapTo(mutableSetOf()) { it.id }
        val retained = selected.filter { it in available }.toImmutableSet()
        id to retained
    }.toMap().toImmutableMap()
    return if (choices == harnessChoices.panes) this else copy(harnessChoices = harnessChoices.copy(panes = choices))
}
