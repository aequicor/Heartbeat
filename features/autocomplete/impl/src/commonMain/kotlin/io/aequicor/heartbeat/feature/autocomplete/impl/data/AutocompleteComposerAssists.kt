package io.aequicor.heartbeat.feature.autocomplete.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.autocomplete.api.AutocompleteEnabled
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssistOrigin
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssists
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerScope
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerTrigger
import io.aequicor.heartbeat.feature.autocomplete.api.HostCommand

/**
 * Profile service merging the suggestion sources of one composer token. The toggle gates everything; a source
 * that is off or unavailable simply contributes nothing, so the popup degrades to fewer sections instead of
 * blocking typing.
 */
@Inject
@ContributesBinding(ProfileScope::class)
internal class AutocompleteComposerAssists(
    private val toggles: FeatureToggles,
) : ComposerAssists {
    override suspend fun suggest(
        trigger: ComposerTrigger,
        scope: ComposerScope,
        hostCommands: List<HostCommand>,
    ): List<ComposerSuggestion> {
        if (!toggles.get(AutocompleteEnabled)) return emptyList()
        if (trigger !is ComposerTrigger.Command) return emptyList()
        return hostCommands.filter { it.matches(trigger.query) }
            .map { it.toSuggestion() }
            .take(SUGGESTION_LIMIT)
    }

    private fun HostCommand.matches(query: String): Boolean {
        if (query.isEmpty()) return true
        val needle = query.lowercase()
        return id.lowercase().startsWith(needle) || label.lowercase().startsWith(needle)
    }

    private fun HostCommand.toSuggestion(): ComposerSuggestion.Command = ComposerSuggestion.Command(
        id = "host:$id",
        label = label,
        description = description,
        origin = ComposerAssistOrigin.Heartbeat,
        insert = insert,
    )

    private companion object {
        const val SUGGESTION_LIMIT = 8
    }
}
