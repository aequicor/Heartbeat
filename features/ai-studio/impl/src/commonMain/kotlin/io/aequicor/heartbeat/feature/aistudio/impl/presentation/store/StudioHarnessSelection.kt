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

/** Harnesses of the chat "+" menu: the library's choices and the user's selection per chat or new-chat pane. */
internal class StudioHarnessSelection(private val harnesses: StudioHarnesses, private val backend: StudioBackend) {
    private val log = Log.tag("AiStudioModel")

    /** Library choices with connections projected from native sessions onto studio chats. */
    suspend fun observe(pipeline: HarnessPipeline) = with(pipeline) {
        combine(harnesses.choices, backend.repository().observeWorkspace()) { choices, workspace ->
            choices.harnesses.toImmutableList() to workspace.sessions.mapNotNull { session ->
                val native = session.nativeSession ?: return@mapNotNull null
                val attached = choices.attached[native] ?: return@mapNotNull null
                session.id to attached.toImmutableSet()
            }.toMap().toImmutableMap()
        }.distinctUntilChanged().collect { (options, chats) ->
            updateState { copy(harnessChoices = harnessChoices.copy(options = options, chats = chats)) }
        }
    }

    /**
     * A chat with a native session is connected in the background, so the screen keeps taking input; the menu
     * follows the committed connections. A new chat keeps the choice on its pane until its first submission.
     */
    suspend fun select(pipeline: HarnessPipeline, intent: AiStudioScreenIntent.SelectHarness) = with(pipeline) {
        var chat: String? = null
        withState { chat = panes.firstOrNull { it.id == intent.paneId }?.sessionId }
        val id = chat
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
        launch { harnesses.connect(native, intent.harness, intent.isSelected) }
    }
}

/** Toggles [harness] in the choice of a new chat's pane. */
internal fun HarnessChoicesUi.choosePane(paneId: Int, harness: String, isSelected: Boolean): HarnessChoicesUi {
    val next = panes[paneId].orEmpty().let { if (isSelected) it + harness else it - harness }
    return copy(
        panes = (if (next.isEmpty()) panes - paneId else panes + (paneId to next.toImmutableSet())).toImmutableMap(),
    )
}

/** The pane's choice left with its submission. */
internal fun HarnessChoicesUi.withoutPane(paneId: Int): HarnessChoicesUi =
    if (paneId in panes) copy(panes = (panes - paneId).toImmutableMap()) else this
