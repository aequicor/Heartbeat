package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet

/** Toggles [harness] in the choice of a new chat's pane. */
fun HarnessChoicesUi.choosePane(paneId: Int, harness: String, isSelected: Boolean): HarnessChoicesUi {
    val next = panes[paneId].orEmpty().let { if (isSelected) it + harness else it - harness }
    return copy(
        panes = (if (next.isEmpty()) panes - paneId else panes + (paneId to next.toImmutableSet())).toImmutableMap(),
    )
}

/**
 * A rejected new chat's choice returns to its pane when its draft does ([draftKey] is the pane's), joined with
 * anything chosen there meanwhile.
 */
fun HarnessChoicesUi.restorePane(submitted: SubmissionUi, draftKey: String): HarnessChoicesUi {
    if (submitted.harnesses.isEmpty() || draftKey != "pane:${submitted.paneId}") return this
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

/** A project switch or library update must not leave invisible selections in a new session's first prompt. */
fun AiStudioScreenState.pruneHarnessChoices(): AiStudioScreenState {
    val choices = harnessChoices.panes.mapNotNull { (id, selected) ->
        val pane = panes.firstOrNull { it.id == id && it.sessionId == null } ?: return@mapNotNull null
        val available = availableHarnesses(pane).mapTo(mutableSetOf()) { it.id }
        val retained = selected.filter { it in available }.toImmutableSet()
        if (retained.isEmpty()) null else id to retained
    }.toMap().toImmutableMap()
    return if (choices == harnessChoices.panes) this else copy(harnessChoices = harnessChoices.copy(panes = choices))
}
