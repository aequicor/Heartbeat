package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerScope
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerTrigger
import io.aequicor.heartbeat.feature.autocomplete.api.HostCommand
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/** Host commands the composer offers; the semantics of each stay with the studio. */
internal fun AiStudioScreenState.hostCommands(): List<HostCommand> =
    if (isRememberEnabled) listOf(HostCommand("remember", "/remember", "", "/remember ")) else emptyList()

/** Engine route, workspace and input limits the pane's suggestions are resolved against. */
internal fun AiStudioScreenState.composerScope(paneId: Int): ComposerScope {
    val pane = panes.firstOrNull { it.id == paneId }
    val sessionId = pane?.sessionId
    val modelId = organisms[sessionId]?.modelId ?: configurations[sessionId]?.modelId
        ?: session(sessionId)?.modelId?.takeIf(String::isNotBlank) ?: settings.modelId
    return ComposerScope(
        target = studioModelTarget(modelId),
        workspace = (pane?.projectId ?: session(sessionId)?.projectId)?.takeIf(String::isNotEmpty)
            ?.let(::WorkspaceRef),
        inputSupport = models.firstOrNull { it.id == modelId }?.inputSupport?.toDomain(),
    )
}

/** A suggestion query queued for the debounced resolve; [token] identifies the dismissed state. */
internal data class SuggestionQuery(
    val draftKey: String,
    val trigger: ComposerTrigger,
    val scope: ComposerScope,
    val hostCommands: List<HostCommand>,
) {
    val token: String get() = "${trigger::class.simpleName}:${trigger.range.first}:${trigger.query}"
}

/** The caret follows the end of a programmatic draft replacement until the editor reports a position. */
internal fun AiStudioScreenState.withDraftCaret(paneId: Int, caret: Int): AiStudioScreenState = copy(
    draftCarets = (draftCarets + (draftKey(paneId) to caret)).toImmutableMap(),
)

/** Removes the pane's suggestions together with their pending query. */
internal fun AiStudioScreenState.withoutSuggestions(paneId: Int): AiStudioScreenState = copy(
    composerSuggestions = (composerSuggestions - draftKey(paneId)).toImmutableMap(),
)

/** Escape hides the list but keeps its token, so typing on does not reopen the same completion. */
internal fun AiStudioScreenState.withDismissedSuggestions(paneId: Int, token: String): AiStudioScreenState = copy(
    composerSuggestions = (
        composerSuggestions + (draftKey(paneId) to ComposerSuggestionsUi(persistentListOf(), 0, token))
        ).toImmutableMap(),
)

/** Published suggestions replace the pane's entry unless that exact token was dismissed. */
internal fun AiStudioScreenState.withSuggestionItems(
    query: SuggestionQuery,
    items: List<io.aequicor.heartbeat.feature.autocomplete.api.ComposerSuggestion>,
): AiStudioScreenState {
    val current = composerSuggestions[query.draftKey]
    val isDismissed = current != null && current.suggestions.isEmpty() && current.token == query.token
    if (isDismissed) return this
    return copy(
        composerSuggestions = (
            composerSuggestions + (query.draftKey to ComposerSuggestionsUi(items.toImmutableList(), 0, query.token))
            ).toImmutableMap(),
    )
}

/** Moves the selection cyclically within the pane's list. */
internal fun AiStudioScreenState.withMovedSuggestion(paneId: Int, delta: Int): AiStudioScreenState {
    val key = draftKey(paneId)
    val list = composerSuggestions[key] ?: return this
    if (list.suggestions.isEmpty()) return this
    val next = (list.selectedIndex + delta).mod(list.suggestions.size)
    return copy(
        composerSuggestions = (composerSuggestions + (key to list.copy(selectedIndex = next))).toImmutableMap(),
    )
}
