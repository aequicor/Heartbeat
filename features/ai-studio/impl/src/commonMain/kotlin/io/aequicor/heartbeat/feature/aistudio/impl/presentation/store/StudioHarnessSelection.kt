package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.PipelineContext

private typealias HarnessPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/** Harnesses of the composer picker: the library's choices and the user's selection per chat or new-chat pane. */
internal class StudioHarnessSelection(private val harnesses: StudioHarnesses, private val backend: StudioBackend) {
    private val log = Log.tag("AiStudioModel")

    /** Library choices with connections projected from native sessions onto studio chats. */
    suspend fun observe(pipeline: HarnessPipeline) = with(pipeline) {
        combine(harnesses.choices, backend.repository().observeWorkspace()) { choices, workspace ->
            choices.harnesses.map { it.toUi() }.toImmutableList() to workspace.sessions.mapNotNull { session ->
                val native = session.nativeSession ?: return@mapNotNull null
                val attached = choices.attached[native] ?: return@mapNotNull null
                session.id to attached.toImmutableSet()
            }.toMap().toImmutableMap()
        }.distinctUntilChanged().collect { (options, chats) ->
            updateState {
                copy(
                    harnessChoices = harnessChoices.copy(options = options, chats = chats),
                ).pruneHarnessChoices()
            }
        }
    }

    /**
     * A chat with a native session is connected in the background, so the screen keeps taking input; the menu
     * follows the committed connections and an unconfirmed change is shown on the chat's pane.
     * A new chat keeps the choice on its pane until its first submission.
     */
    suspend fun select(pipeline: HarnessPipeline, intent: AiStudioScreenIntent.SelectHarness) = with(pipeline) {
        var target: PaneUi? = null
        withState {
            target = panes.firstOrNull { pane ->
                pane.id == intent.paneId && !pane.isCreating && availableHarnesses(pane).any { it.id == intent.harness }
            }
        }
        val pane = target ?: return@with
        val id = pane.sessionId
        if (id == null) {
            log.i { "New chat harness choice selected=${intent.isSelected}" }
            updateState {
                copy(
                    harnessChoices = harnessChoices.choosePane(intent.paneId, intent.harness, intent.isSelected),
                )
            }
            return@with
        }
        val native = backend.repository().observeWorkspace().first().session(id)?.nativeSession
        if (native == null) {
            log.w { "Chat harness choice ignored: the chat has no native session yet" }
            return@with
        }
        log.i { "Chat harness connection requested attach=${intent.isSelected}" }
        updateState { copy(harnessChoices = harnessChoices.failed(id, isFailed = false)) }
        launch {
            val isConfirmed = harnesses.connect(native, intent.harness, intent.isSelected)
            updateState { copy(harnessChoices = harnessChoices.failed(id, isFailed = !isConfirmed)) }
        }
    }
}

/** Marks or clears an unconfirmed connection change of [chat]. */
private fun HarnessChoicesUi.failed(chat: String, isFailed: Boolean): HarnessChoicesUi = when {
    isFailed && chat !in failedChats -> copy(failedChats = (failedChats + chat).toImmutableSet())
    !isFailed && chat in failedChats -> copy(failedChats = (failedChats - chat).toImmutableSet())
    else -> this
}

/** Toggles [harness] in the choice of a new chat's pane. */
internal fun HarnessChoicesUi.choosePane(paneId: Int, harness: String, isSelected: Boolean): HarnessChoicesUi {
    val next = panes[paneId].orEmpty().let { if (isSelected) it + harness else it - harness }
    return copy(
        panes = (if (next.isEmpty()) panes - paneId else panes + (paneId to next.toImmutableSet())).toImmutableMap(),
    )
}

/**
 * A rejected new chat's choice returns to its pane when its draft does ([draftKey] is the pane's), joined with
 * anything chosen there meanwhile.
 */
internal fun HarnessChoicesUi.restorePane(submitted: SubmissionUi, draftKey: String): HarnessChoicesUi {
    if (submitted.harnesses.isEmpty() || draftKey != "pane:${submitted.paneId}") return this
    val restored = panes[submitted.paneId].orEmpty() + submitted.harnesses
    return copy(panes = (panes + (submitted.paneId to restored.toImmutableSet())).toImmutableMap())
}

/** The pane's choice left with its submission. */
internal fun HarnessChoicesUi.withoutPane(paneId: Int): HarnessChoicesUi =
    if (paneId in panes) copy(panes = (panes - paneId).toImmutableMap()) else this

/** Shared harnesses plus this project's choices; connected chat harnesses remain reachable for disconnection. */
internal fun AiStudioScreenState.availableHarnesses(pane: PaneUi): List<HarnessOptionUi> {
    val projectId = pane.projectId ?: sessions.firstOrNull { it.id == pane.sessionId }?.projectId
    val attached = pane.sessionId?.let { harnessChoices.chats[it] }.orEmpty()
    return harnessChoices.options.filter { it.isAvailableFor(projectId) || it.id in attached }
}

/** A project switch or library update must not leave invisible selections in a new session's first prompt. */
internal fun AiStudioScreenState.pruneHarnessChoices(): AiStudioScreenState {
    val choices = harnessChoices.panes.mapNotNull { (id, selected) ->
        val pane = panes.firstOrNull { it.id == id && it.sessionId == null } ?: return@mapNotNull null
        val available = availableHarnesses(pane).mapTo(mutableSetOf()) { it.id }
        val retained = selected.filter { it in available }.toImmutableSet()
        if (retained.isEmpty()) null else id to retained
    }.toMap().toImmutableMap()
    return if (choices == harnessChoices.panes) this else copy(harnessChoices = harnessChoices.copy(panes = choices))
}
