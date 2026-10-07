package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssists
import io.aequicor.heartbeat.feature.autocomplete.api.applyComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.composerTrigger
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import pro.respawn.flowmvi.api.PipelineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private typealias SuggestionsPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/**
 * Composer autocomplete of the studio screen. Draft and caret changes queue a debounced
 * [ComposerAssists] query for the pane's scope; the resolved list lands in the pane's state with keyboard
 * navigation, Escape hides the exact token until it changes, and acceptance replaces the token in place.
 * An attached file is reported to [onAttachFile], which routes it through the studio's own import pipeline.
 */
@OptIn(FlowPreview::class)
internal class StudioSuggestions(
    private val assists: ComposerAssists,
    private val onAttachFile: suspend (paneId: Int, location: String) -> Unit,
) {
    private val log = Log.tag("StudioSuggestions")

    /** Latest suggestion request; null hides the popup. The debounced collector resolves it. */
    private val queries = MutableStateFlow<SuggestionQuery?>(null)

    /** Dispatches one autocomplete interaction of a pane's composer. */
    internal suspend fun handle(pipeline: SuggestionsPipeline, intent: AiStudioScreenIntent.Suggestions) {
        when (intent) {
            is AiStudioScreenIntent.Suggestions.DraftChanged -> with(pipeline) {
                updateState { withDraft(intent.paneId, intent.text).withDraftCaret(intent.paneId, intent.text.length) }
                queue(pipeline, intent.paneId)
            }

            is AiStudioScreenIntent.Suggestions.CaretMoved -> with(pipeline) {
                updateState { withDraftCaret(intent.paneId, intent.caret) }
                queue(pipeline, intent.paneId)
            }

            is AiStudioScreenIntent.Suggestions.MoveSuggestion -> with(pipeline) {
                updateState { withMovedSuggestion(intent.paneId, intent.delta) }
            }

            is AiStudioScreenIntent.Suggestions.AcceptSuggestion -> accept(pipeline, intent.paneId, intent.index)

            is AiStudioScreenIntent.Suggestions.DismissSuggestions -> dismiss(pipeline, intent.paneId)
        }
    }

    /** Resolves the pane's active token; no token clears the list and its pending query. */
    internal suspend fun queue(pipeline: SuggestionsPipeline, paneId: Int) {
        with(pipeline) {
            withState {
                val trigger = composerTrigger(draft(paneId), draftCaret(paneId))
                if (trigger == null) {
                    queries.value = null
                    updateState { withoutSuggestions(paneId) }
                    return@withState
                }
                val query = SuggestionQuery(draftKey(paneId), trigger, composerScope(paneId), hostCommands())
                val dismissed = composerSuggestions[query.draftKey]
                    ?.takeIf { it.items.isEmpty() && it.token == query.token }
                if (dismissed == null) {
                    log.v { "suggestion query queued kind=${query.trigger::class.simpleName}" }
                    queries.value = query
                }
            }
        }
    }

    /** Debounced publication of suggestions; a newer query cancels an obsolete resolve. */
    internal suspend fun observe(pipeline: SuggestionsPipeline) {
        queries
            .debounce { if (it == null) Duration.ZERO else SUGGESTION_DEBOUNCE }
            .collectLatest { query ->
                if (query == null) return@collectLatest
                val items = try {
                    assists.suggest(query.trigger, query.scope, query.hostCommands)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "composer suggestions failed" }
                    emptyList()
                }
                with(pipeline) {
                    updateState { withSuggestionItems(query, items) }
                }
            }
    }

    private suspend fun dismiss(pipeline: SuggestionsPipeline, paneId: Int) {
        with(pipeline) {
            withState {
                val token = composerSuggestions[draftKey(paneId)]?.token
                    ?: composerTrigger(draft(paneId), draftCaret(paneId))?.tokenKey()
                if (token == null) {
                    log.v { "suggestion query dropped with no active token" }
                    queries.value = null
                    updateState { withoutSuggestions(paneId) }
                } else {
                    updateState { withDismissedSuggestions(paneId, token) }
                }
            }
        }
    }

    private suspend fun accept(pipeline: SuggestionsPipeline, paneId: Int, index: Int?) {
        with(pipeline) {
            withState {
                val key = draftKey(paneId)
                val list = composerSuggestions[key] ?: return@withState
                val suggestion = list.items.getOrNull(index ?: list.selectedIndex)
                    ?.takeIf { it.kind != SuggestionKindUi.File || it.isSupported } ?: return@withState
                val trigger = composerTrigger(draft(paneId), draftCaret(paneId)) ?: return@withState
                val applied = applyComposerSuggestion(draft(paneId), trigger, suggestion.toCompletion())
                log.i { "composer suggestion accepted kind=${suggestion.kind}" }
                updateState {
                    copy(
                        drafts = (drafts + (key to applied.text)).toImmutableMap(),
                        draftCarets = (draftCarets + (key to applied.caret)).toImmutableMap(),
                        composerSuggestions = (composerSuggestions - key).toImmutableMap(),
                    )
                }
                applied.attach?.let { file -> onAttachFile(paneId, file.location) }
            }
        }
    }

    private companion object {
        val SUGGESTION_DEBOUNCE: Duration = 200.milliseconds
    }
}
