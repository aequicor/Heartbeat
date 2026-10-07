package io.aequicor.heartbeat.feature.autocomplete.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.attachments.api.attachmentMediaTypeFor
import io.aequicor.heartbeat.feature.autocomplete.api.AutocompleteEnabled
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssistOrigin
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssists
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerScope
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerTrigger
import io.aequicor.heartbeat.feature.autocomplete.api.HostCommand
import io.aequicor.heartbeat.feature.autocomplete.impl.domain.fileRank
import io.aequicor.heartbeat.feature.autocomplete.impl.domain.queryRank

/**
 * Profile service merging the suggestion sources of one composer token. Heartbeat items come first, native
 * engine ones after them, and a source that is off or unavailable contributes nothing — typing degrades to
 * fewer sections instead of blocking. Matching is prefix-then-substring over ids, titles and labels; a native
 * item that duplicates a Heartbeat item's visible name drops, because the host item owns the semantics the
 * composer can guarantee.
 */
@Inject
@ContributesBinding(ProfileScope::class)
internal class AutocompleteComposerAssists(
    private val toggles: FeatureToggles,
    private val learnedSkills: LearnedSkillsSource,
    private val engineAssists: EngineAssistsSource,
    private val projectFiles: ProjectFileIndex,
) : ComposerAssists {
    override suspend fun suggest(
        trigger: ComposerTrigger,
        scope: ComposerScope,
        hostCommands: List<HostCommand>,
    ): List<ComposerSuggestion> {
        if (!toggles.get(AutocompleteEnabled)) return emptyList()
        val merged = when (trigger) {
            is ComposerTrigger.Command -> buildList {
                hostCommands.filter { it.matches(trigger.query) }.forEach { add(hostCommand(it)) }
                native(trigger, scope).forEach { add(it) }
            }

            is ComposerTrigger.Mention -> buildList {
                learned(trigger, scope).forEach { add(it) }
                native(trigger, scope).forEach { add(it) }
                files(trigger, scope).forEach { add(it) }
            }
        }
        val heartbeatNames = merged.asSequence()
            .filter { it.suggestion.origin == ComposerAssistOrigin.Heartbeat }
            .map { it.suggestion.label.lowercase() }
            .toSet()
        return merged
            .filterNot {
                it.suggestion.origin != ComposerAssistOrigin.Heartbeat &&
                    it.suggestion.label.lowercase() in heartbeatNames
            }
            .sortedBy { it.rank }
            .map { it.suggestion }
            .take(SUGGESTION_LIMIT)
    }

    private fun HostCommand.matches(query: String): Boolean = queryRank(query, id) >= 0 || queryRank(query, label) >= 0

    private fun hostCommand(command: HostCommand): Ranked = Ranked(
        ComposerSuggestion.Command(
            id = "host:${command.id}",
            label = command.label,
            description = command.description,
            origin = ComposerAssistOrigin.Heartbeat,
            insert = command.insert,
        ),
        HOST_RANK,
    )

    private suspend fun learned(trigger: ComposerTrigger.Mention, scope: ComposerScope): List<Ranked> {
        if (!toggles.get(AgentLearningEnabled)) return emptyList()
        return learnedSkills.skills(scope.workspace).mapNotNull { skill ->
            rank(trigger.query, skill.title)?.let {
                Ranked(
                    ComposerSuggestion.Skill(
                        id = "host:${skill.id.value}",
                        label = skill.title,
                        description = skill.description,
                        origin = ComposerAssistOrigin.Heartbeat,
                        insert = "${skill.title} ",
                    ),
                    it,
                )
            }
        }
    }

    private suspend fun native(trigger: ComposerTrigger, scope: ComposerScope): List<Ranked> {
        val target = scope.target ?: return emptyList()
        val kind = when (trigger) {
            is ComposerTrigger.Command -> EngineAssist.Kind.Command
            is ComposerTrigger.Mention -> EngineAssist.Kind.Skill
        }
        return engineAssists.assists(target, scope.workspace)
            .filter { it.kind == kind }
            .mapNotNull { assist -> rank(trigger.query, assist.label, assist.id)?.let { assist to it } }
            .map { (assist, rank) ->
                val origin = ComposerAssistOrigin.Engine(target.engine)
                val suggestion = if (kind == EngineAssist.Kind.Command) {
                    ComposerSuggestion.Command(
                        id = "engine:${target.engine.value}:${assist.id}",
                        label = assist.label,
                        description = assist.description,
                        origin = origin,
                        insert = assist.insert,
                    )
                } else {
                    ComposerSuggestion.Skill(
                        id = "engine:${target.engine.value}:${assist.id}",
                        label = assist.label,
                        description = assist.description,
                        origin = origin,
                        insert = assist.insert,
                    )
                }
                Ranked(suggestion, rank)
            }
    }

    private suspend fun files(trigger: ComposerTrigger.Mention, scope: ComposerScope): List<Ranked> {
        if (!toggles.get(AttachmentsEnabled)) return emptyList()
        val support = scope.inputSupport ?: return emptyList()
        if (support.allowedMediaTypes.isEmpty()) return emptyList()
        return projectFiles.search(scope.workspace, trigger.query, SUGGESTION_LIMIT)
            .map { file ->
                val mediaType = file.mediaType ?: attachmentMediaTypeFor(file.relativePath)
                // A file the current model cannot take stays visible with the reason instead of disappearing.
                val reason = when {
                    mediaType == null || mediaType !in support.allowedMediaTypes -> "unsupported format"
                    file.sizeBytes > support.maxFileBytes -> "too large"
                    else -> null
                }
                Ranked(
                    ComposerSuggestion.File(
                        id = "file:${file.relativePath}",
                        label = file.relativePath,
                        description = describeSize(file.sizeBytes) + (reason?.let { " · $it" } ?: ""),
                        origin = ComposerAssistOrigin.Heartbeat,
                        relativePath = file.relativePath,
                        location = file.location,
                        sizeBytes = file.sizeBytes,
                        mediaType = mediaType,
                        isSupported = reason == null,
                    ),
                    fileRank(trigger.query, file.relativePath).coerceAtLeast(0),
                )
            }
    }

    private fun describeSize(sizeBytes: Long): String = when {
        sizeBytes >= BYTES_PER_MB -> "${sizeBytes / BYTES_PER_MB} MB"
        sizeBytes >= BYTES_PER_KB -> "${sizeBytes / BYTES_PER_KB} kB"
        else -> "$sizeBytes B"
    }

    private fun rank(query: String, vararg candidates: String): Int? {
        val best = candidates.minOf { queryRank(query, it) }
        return best.takeIf { it >= 0 }
    }

    private data class Ranked(val suggestion: ComposerSuggestion, val rank: Int)

    private companion object {
        const val SUGGESTION_LIMIT = 8
        const val HOST_RANK = -1
        const val BYTES_PER_KB = 1_024L
        const val BYTES_PER_MB = 1_048_576L
    }
}
