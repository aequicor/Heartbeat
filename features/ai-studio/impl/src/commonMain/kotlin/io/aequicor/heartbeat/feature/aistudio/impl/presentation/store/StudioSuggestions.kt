package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssists
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.applyComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.composerTrigger
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.PipelineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private typealias SuggestionsPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/**
 * Screen-owned completion requests. Every state publication synchronously invalidates suggestions whose text,
 * caret or effective route changed, including changes coming from the machine rather than editor intents.
 * Debouncing delays only the new lookup. A generation identifies its result and the user's acceptance, so a
 * late source result cannot revive a dismissed token or attach a file from a previous workspace.
 */
internal class StudioSuggestions(
    private val assists: ComposerAssists,
    private val isFileInputAllowed: Boolean,
    private val onAttachFile: suspend (paneId: Int, location: String) -> Unit,
) {
    private val log = Log.tag("StudioSuggestions")
    private val requests = MutableStateFlow<Map<String, Request>>(emptyMap())
    private val dismissed = mutableMapOf<String, SuggestionQuery>()
    private var nextRequest = 0L
    private var nextCaretRequest = 0L

    /** Runs inside the store's state publication hook, before obsolete rows can reach the editor. */
    @HighFrequency
    internal fun onState(state: AiStudioScreenState): AiStudioScreenState {
        val desired = state.panes.mapNotNull { pane -> state.query(pane.id) }.associateBy { it.draftKey }
        dismissed.keys.retainAll(desired.keys)
        val previous = requests.value
        val current = desired.mapNotNull { (key, query) ->
            if (dismissed[key] == query) {
                null
            } else {
                dismissed.remove(key)
                key to (previous[key]?.takeIf { it.query == query } ?: Request(++nextRequest, query))
            }
        }.toMap()
        if (previous != current) log.v { "composer requests changed count=${current.size}" }
        requests.value = current
        val visible = state.composerSuggestions.filter { (key, list) -> current[key]?.id == list.requestId }
        return if (visible.size == state.composerSuggestions.size) {
            state
        } else {
            state.copy(composerSuggestions = visible.toImmutableMap())
        }
    }

    /** Dispatches local draft and completion interactions; state publication queues their lookups. */
    internal suspend fun handle(pipeline: SuggestionsPipeline, intent: AiStudioScreenIntent.Suggestions) {
        with(pipeline) {
            when (intent) {
                is AiStudioScreenIntent.Suggestions.DraftChanged -> updateState {
                    withDraft(intent.paneId, intent.text).withDraftCaret(intent.paneId, intent.text.length)
                }

                is AiStudioScreenIntent.Suggestions.CaretMoved -> updateState {
                    withDraftCaret(intent.paneId, intent.caret)
                }

                is AiStudioScreenIntent.Suggestions.CaretRequestApplied -> updateState {
                    val key = draftKey(intent.paneId)
                    if (draftCaretRequests[key]?.id == intent.id) {
                        copy(draftCaretRequests = (draftCaretRequests - key).toImmutableMap())
                    } else {
                        this
                    }
                }

                is AiStudioScreenIntent.Suggestions.MoveSuggestion -> updateState {
                    withMovedSuggestion(intent.paneId, intent.delta)
                }

                is AiStudioScreenIntent.Suggestions.AcceptSuggestion -> accept(pipeline, intent.paneId, intent.index)

                is AiStudioScreenIntent.Suggestions.DismissSuggestions -> withState {
                    val key = draftKey(intent.paneId)
                    val current = query(intent.paneId)
                    if (current != null) dismissed[key] = current
                    updateState { withoutSuggestions(intent.paneId) }
                }
            }
        }
    }

    /** Cancels stale lookups immediately; each replacement lookup waits for a pause in editing. */
    internal suspend fun observe(pipeline: SuggestionsPipeline) {
        requests.collectLatest { current ->
            coroutineScope {
                current.values.forEach { request ->
                    launch {
                        delay(SUGGESTION_DEBOUNCE)
                        resolve(pipeline, request)
                    }
                }
            }
        }
    }

    private suspend fun resolve(pipeline: SuggestionsPipeline, request: Request) {
        val query = request.query
        val items = try {
            assists.suggest(query.trigger, query.scope, query.hostCommands)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "composer suggestions failed" }
            emptyList()
        }
        with(pipeline) {
            updateState {
                if (requests.value[query.draftKey] == request) {
                    withSuggestionItems(
                        query,
                        items.filter { isFileInputAllowed || it !is ComposerSuggestion.File },
                        request.id,
                    )
                } else {
                    this
                }
            }
        }
    }

    private suspend fun accept(pipeline: SuggestionsPipeline, paneId: Int, index: Int?) {
        with(pipeline) {
            withState {
                val key = draftKey(paneId)
                val request = requests.value[key] ?: return@withState
                if (query(paneId) != request.query) return@withState
                val list = composerSuggestions[key]?.takeIf { it.requestId == request.id } ?: return@withState
                val suggestion = list.items.getOrNull(index ?: list.selectedIndex)
                    ?.takeIf { it.kind != SuggestionKindUi.File || (isFileInputAllowed && it.isSupported) }
                    ?: return@withState
                val applied = applyComposerSuggestion(draft(paneId), request.query.trigger, suggestion.toCompletion())
                val caretRequest = ComposerCaretRequestUi(++nextCaretRequest, applied.text, applied.caret)
                log.i { "composer suggestion accepted kind=${suggestion.kind}" }
                updateState {
                    copy(
                        drafts = (drafts + (key to applied.text)).toImmutableMap(),
                        draftCarets = (draftCarets + (key to applied.caret)).toImmutableMap(),
                        draftCaretRequests = (draftCaretRequests + (key to caretRequest)).toImmutableMap(),
                        composerSuggestions = (composerSuggestions - key).toImmutableMap(),
                    )
                }
                val file = applied.attach
                if (file != null) onAttachFile(paneId, file.location)
            }
        }
    }

    @HighFrequency
    private fun AiStudioScreenState.query(paneId: Int): SuggestionQuery? {
        val text = draft(paneId)
        val caret = draftCaret(paneId)
        val trigger = composerTrigger(text, caret) ?: return null
        return SuggestionQuery(draftKey(paneId), trigger, composerScope(paneId), hostCommands(), text, caret)
    }

    private data class Request(val id: Long, val query: SuggestionQuery)

    private companion object {
        val SUGGESTION_DEBOUNCE = 200.milliseconds
    }
}
