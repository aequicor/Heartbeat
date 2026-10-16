package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioHarnesses
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenAction
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.HarnessChoicesUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.availableHarnesses
import io.aequicor.heartbeat.feature.aistudio.screenmodel.choosePane
import io.aequicor.heartbeat.feature.aistudio.screenmodel.pruneHarnessChoices
import io.aequicor.heartbeat.feature.aistudio.screenmodel.selectedHarnesses
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.PipelineContext

/** Harnesses of the composer picker: the library's choices and the user's selection per chat or new-chat pane. */
internal class StudioHarnessSelection(private val harnesses: StudioHarnesses, private val backend: StudioBackend) {
    private val log = Log.tag("AiStudioModel")

    /**
     * Library choices projected onto chats. Equal failures must still be delivered after an inline retry clears
     * the displayed failure; loading itself does not replace the last library snapshot.
     */
    suspend fun observe(pipeline: HarnessPipeline) = with(pipeline) {
        combine(harnesses.choices, backend.repository().observeWorkspace()) { choices, workspace ->
            val chats = workspace.sessions.mapNotNull { session ->
                val native = session.nativeSession ?: return@mapNotNull null
                val attached = choices.attached[native] ?: return@mapNotNull null
                session.id to attached.toImmutableSet()
            }.toMap().toImmutableMap()
            Triple(choices, choices.harnesses.map { it.toUi() }.toImmutableList(), chats)
        }.collect { (choices, options, chats) ->
            updateState {
                if (choices.isReady) {
                    copy(
                        harnessChoices = harnessChoices.copy(
                            options = options,
                            chats = chats,
                            isReady = true,
                            isLoadFailed = false,
                        ),
                    ).pruneHarnessChoices()
                } else if (choices.isLoadFailed && harnessChoices.isReady) {
                    this
                } else {
                    copy(harnessChoices = harnessChoices.copy(isReady = false, isLoadFailed = choices.isLoadFailed))
                }
            }
        }
    }

    /** A failed initial load has an inline retry; input and manual selections remain intact. */
    suspend fun reload(pipeline: HarnessPipeline) = with(pipeline) {
        updateState { copy(harnessChoices = harnessChoices.copy(isLoadFailed = false)) }
        if (!harnesses.reload()) updateState { copy(harnessChoices = harnessChoices.copy(isLoadFailed = true)) }
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
                    harnessChoices = harnessChoices.choosePane(
                        intent.paneId,
                        intent.harness,
                        intent.isSelected,
                        selectedHarnesses(pane),
                    ),
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

private typealias HarnessPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/** Marks or clears an unconfirmed connection change of [chat]. */
private fun HarnessChoicesUi.failed(chat: String, isFailed: Boolean): HarnessChoicesUi = when {
    isFailed && chat !in failedChats -> copy(failedChats = (failedChats + chat).toImmutableSet())
    !isFailed && chat in failedChats -> copy(failedChats = (failedChats - chat).toImmutableSet())
    else -> this
}
