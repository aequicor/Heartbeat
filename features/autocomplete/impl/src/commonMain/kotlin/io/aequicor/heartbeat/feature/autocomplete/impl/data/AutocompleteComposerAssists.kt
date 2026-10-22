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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Profile service merging the suggestion sources of one composer token by relevance across origins.
 * A source that is off or unavailable contributes nothing — typing degrades to
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
    override suspend fun isAvailable(suggestion: ComposerSuggestion, scope: ComposerScope): Boolean {
        val origin = suggestion.origin as? ComposerAssistOrigin.Engine ?: return true
        val target = scope.target?.takeIf { it.engine == origin.engine } ?: return false
        return engineAssists.assists(target, scope.workspace).any {
            suggestion.id == "engine:${target.engine.value}:${it.id}"
        }
    }

    override suspend fun prewarm(scope: ComposerScope) {
        suggest(ComposerTrigger.Mention(0..0, ""), scope, emptyList())
    }

    override suspend fun suggest(
        trigger: ComposerTrigger,
        scope: ComposerScope,
        hostCommands: List<HostCommand>,
    ): List<ComposerSuggestion> = coroutineScope {
        if (!toggles.get(AutocompleteEnabled)) return@coroutineScope emptyList()
        val engine = async { native(trigger, scope) }
        val files = async { files(trigger, scope) }
        val host = when (trigger) {
            is ComposerTrigger.Command -> hostCommands.filter { it.matches(trigger.query) }
                .map { hostCommand(it, trigger.query) } + learned(trigger, scope)

            is ComposerTrigger.Mention -> learned(trigger, scope)
        }
        merge(host + engine.await() + files.await())
    }

    override fun observe(
        trigger: ComposerTrigger,
        scope: ComposerScope,
        hostCommands: List<HostCommand>,
    ): Flow<List<ComposerSuggestion>> = flow {
        if (!toggles.get(AutocompleteEnabled)) {
            emit(emptyList())
            return@flow
        }
        val host = when (trigger) {
            is ComposerTrigger.Command -> hostCommands.filter { it.matches(trigger.query) }
                .map { hostCommand(it, trigger.query) } + learned(trigger, scope)

            is ComposerTrigger.Mention -> learned(trigger, scope)
        }
        emit(merge(host))
        val native = scope.target?.let { target ->
            engineAssists.observe(target, scope.workspace).map { nativeItems(trigger, scope, it) }
        } ?: flow { emit(emptyList()) }
        val files = flow {
            emit(files(trigger, scope))
        }.onStart { emit(emptyList()) }
        emitAll(combine(native, files) { engine, workspace -> merge(host + engine + workspace) })
    }.distinctUntilChanged()

    private fun merge(merged: List<Ranked>): List<ComposerSuggestion> {
        val nativeSkillNames = merged.filter {
            it.suggestion is ComposerSuggestion.Skill &&
                it.suggestion.origin is ComposerAssistOrigin.Engine
        }.map { it.suggestion.duplicateKey() }.toSet()
        val candidates = merged.filterNot {
            it.suggestion.id.startsWith("project-skill:") &&
                it.suggestion.duplicateKey() in nativeSkillNames
        }
        val heartbeatNames = candidates.asSequence()
            .filter { it.suggestion.origin == ComposerAssistOrigin.Heartbeat }
            .map { it.suggestion.duplicateKey() }
            .toSet()
        return candidates.asSequence()
            .filterNot {
                it.suggestion.origin != ComposerAssistOrigin.Heartbeat &&
                    it.suggestion.duplicateKey() in heartbeatNames
            }
            .sortedWith(compareBy({ it.rank }, { it.suggestion.origin != ComposerAssistOrigin.Heartbeat }))
            .map { it.suggestion }
            .distinctBy { it.duplicateKey() }
            .take(SUGGESTION_LIMIT)
            .toList()
    }

    private fun ComposerSuggestion.duplicateKey(): String = when (this) {
        is ComposerSuggestion.Command -> "command:${label.lowercase()}"
        is ComposerSuggestion.Skill -> "skill:${label.lowercase()}"
        is ComposerSuggestion.File -> "file:$relativePath"
    }

    private fun HostCommand.matches(query: String): Boolean = queryRank(query, id) >= 0 || queryRank(query, label) >= 0

    private fun hostCommand(command: HostCommand, query: String): Ranked = Ranked(
        ComposerSuggestion.Command(
            id = "host:${command.id}",
            label = command.label,
            description = command.description,
            origin = ComposerAssistOrigin.Heartbeat,
            insert = command.insert,
        ),
        rank(query, command.id, command.label) ?: 0,
    )

    private suspend fun learned(trigger: ComposerTrigger, scope: ComposerScope): List<Ranked> {
        if (!toggles.get(AgentLearningEnabled)) return emptyList()
        return learnedSkills.skills(scope.project).mapNotNull { skill ->
            rank(trigger.query, skill.title)?.let {
                Ranked(
                    ComposerSuggestion.Skill(
                        id = "host:${skill.id.value}",
                        label = skill.title,
                        description = skill.description,
                        origin = ComposerAssistOrigin.Heartbeat,
                        insert = "@${skill.title} ",
                    ),
                    it,
                )
            }
        }
    }

    private suspend fun native(trigger: ComposerTrigger, scope: ComposerScope): List<Ranked> {
        val target = scope.target ?: return emptyList()
        return nativeItems(trigger, scope, engineAssists.assists(target, scope.workspace))
    }

    private fun nativeItems(trigger: ComposerTrigger, scope: ComposerScope, assists: List<EngineAssist>): List<Ranked> {
        val target = scope.target ?: return emptyList()
        return assists.asSequence()
            .filter { trigger is ComposerTrigger.Command || it.kind == EngineAssist.Kind.Skill }
            .mapNotNull { assist -> rank(trigger.query, assist.label, assist.id)?.let { assist to it } }
            .map { (assist, rank) ->
                val origin = ComposerAssistOrigin.Engine(target.engine)
                val suggestion = if (assist.kind == EngineAssist.Kind.Command) {
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
            .toList()
    }

    private suspend fun files(trigger: ComposerTrigger, scope: ComposerScope): List<Ranked> {
        val support = scope.inputSupport
        val areFilesAllowed = trigger is ComposerTrigger.Mention && toggles.get(AttachmentsEnabled) &&
            support != null && support.allowedMediaTypes.isNotEmpty()
        return projectFiles.search(scope.workspace, trigger.query, SUGGESTION_LIMIT, skillsOnly = !areFilesAllowed)
            .mapNotNull { file ->
                file.skill?.let { return@mapNotNull projectSkill(file, it, trigger.query) }
                if (!areFilesAllowed || support == null) return@mapNotNull null
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
                        label = file.relativePath.substringAfterLast('/'),
                        description = "${file.relativePath} · ${describeSize(file.sizeBytes)}" +
                            reason?.let { " · $it" }.orEmpty(),
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

    private fun projectSkill(file: ProjectFile, skill: ProjectSkill, query: String): Ranked = Ranked(
        ComposerSuggestion.Skill(
            id = "project-skill:${file.relativePath}",
            label = skill.name,
            description = skill.description,
            origin = ComposerAssistOrigin.Heartbeat,
            // The path stays in the prompt so file-capable agents can read the exact instruction.
            // The editor displays only the label, without importing an attachment.
            insert = "[/" + skill.name + "](" + file.relativePath.replace("%", "%25").replace(" ", "%20")
                .replace("(", "%28").replace(")", "%29").replace("#", "%23") + ") ",
        ),
        rank(query, skill.name, file.relativePath) ?: 0,
    )

    private fun describeSize(sizeBytes: Long): String = when {
        sizeBytes >= BYTES_PER_MB -> "${sizeBytes / BYTES_PER_MB} MB"
        sizeBytes >= BYTES_PER_KB -> "${sizeBytes / BYTES_PER_KB} kB"
        else -> "$sizeBytes B"
    }

    private fun rank(query: String, vararg candidates: String): Int? =
        candidates.asSequence().map { queryRank(query, it) }.filter { it >= 0 }.minOrNull()

    private data class Ranked(val suggestion: ComposerSuggestion, val rank: Int)

    private companion object {
        const val SUGGESTION_LIMIT = 16
        const val BYTES_PER_KB = 1_024L
        const val BYTES_PER_MB = 1_048_576L
    }
}
