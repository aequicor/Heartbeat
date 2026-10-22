package io.aequicor.heartbeat.feature.autocomplete.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Composer context of one pane: engine route, effective workspace and input limits of its model. */
public data class ComposerScope(
    public val target: EngineTarget? = null,
    public val workspace: WorkspaceRef? = null,
    public val inputSupport: PromptInputSupport? = null,
    /** Logical project owning learned skills; a worktree keeps its original project here. */
    public val project: WorkspaceRef? = workspace,
)

/** Host-authored command offered by the calling screen; [insert] replaces the typed token. */
public data class HostCommand(
    public val id: String,
    public val label: String,
    public val description: String = "",
    public val insert: String,
)

/** Where a suggestion comes from: Heartbeat itself, or a native engine of the pane's route. */
public sealed interface ComposerAssistOrigin {
    /** Offered by Heartbeat's own features. */
    public data object Heartbeat : ComposerAssistOrigin

    /** Offered by the native runtime of [engine]. */
    public data class Engine(public val engine: EngineId) : ComposerAssistOrigin
}

/** One offered completion of the active composer token. */
public sealed interface ComposerSuggestion {
    public val id: String
    public val label: String
    public val description: String
    public val origin: ComposerAssistOrigin

    /**
     * A command inserted as text: a Heartbeat command such as `/remember` (whose behaviour the host screen owns)
     * or a native engine command whose [insert] follows the engine's own convention.
     */
    public data class Command(
        override val id: String,
        override val label: String,
        override val description: String,
        override val origin: ComposerAssistOrigin,
        public val insert: String,
    ) : ComposerSuggestion

    /** A skill referenced by its name; the agent loads its body when the task matches. */
    public data class Skill(
        override val id: String,
        override val label: String,
        override val description: String,
        override val origin: ComposerAssistOrigin,
        public val insert: String,
    ) : ComposerSuggestion

    /**
     * A workspace file completed to its full relative path and attached as an input of the draft.
     * [location] is the transient native source of one accepted suggestion; it is never persisted or logged.
     * [isSupported] is false when the scope's model cannot take the file; it stays visible with the reason.
     */
    public data class File(
        override val id: String,
        override val label: String,
        override val description: String,
        override val origin: ComposerAssistOrigin,
        public val relativePath: String,
        public val location: String,
        public val sizeBytes: Long,
        public val mediaType: String?,
        public val isSupported: Boolean = true,
    ) : ComposerSuggestion {
        override fun toString(): String = "ComposerSuggestion.File(path=$relativePath, size=$sizeBytes)"
    }
}

/** The composer token the caret is editing. [range] includes the trigger character and spans the whole word. */
public sealed interface ComposerTrigger {
    public val range: IntRange

    /** The text typed between the trigger character and the caret. */
    public val query: String

    /** `/` at line start or `$` after whitespace; offers commands and skills, including an empty query. */
    public data class Command(override val range: IntRange, override val query: String) : ComposerTrigger

    /** `@` after whitespace or at the start of a line; needs at least one typed character. */
    public data class Mention(override val range: IntRange, override val query: String) : ComposerTrigger
}

/** Draft state after applying a suggestion. */
public data class ComposerDraft(
    public val text: String,
    public val caret: Int,
    /** A file suggestion the owner must import as an attachment of the draft. */
    public val attach: ComposerSuggestion.File? = null,
)

/**
 * Instruction body of a project skill linked in a prompt (`[/name](path/SKILL.md)`). The host delivers [content]
 * with the prompt, so a model without file tools still receives the instruction the short label stands for.
 */
public data class ComposerSkillContent(
    public val label: String,
    public val relativePath: String,
    public val content: String,
) {
    override fun toString(): String = "ComposerSkillContent(path=$relativePath, length=${content.length})"
}

/**
 * Main-safe suggestion service of one profile. Suggest never starts processes on its own, ranks the merged
 * result by relevance across origins (Heartbeat wins ties), and returns an empty list to hide the popup.
 */
public interface ComposerAssists {
    /** Revalidates a displayed item and its native discovery permissions before acceptance. */
    public suspend fun isAvailable(suggestion: ComposerSuggestion, scope: ComposerScope): Boolean = true

    /** Warms bounded source caches when the editor gains focus or its route changes; cancellation stops the work. */
    public suspend fun prewarm(scope: ComposerScope): Unit = Unit

    /** Progressive snapshots for one token; sources run independently and the caller cancels obsolete requests. */
    public fun observe(
        trigger: ComposerTrigger,
        scope: ComposerScope,
        hostCommands: List<HostCommand> = emptyList(),
    ): Flow<List<ComposerSuggestion>> = flow { emit(suggest(trigger, scope, hostCommands)) }

    /**
     * Bodies of the project skills linked in [text], read again from [workspace] at send time. A link that no longer
     * resolves to a valid skill file of the workspace contributes nothing; the prompt itself keeps the link.
     */
    public suspend fun referencedSkills(text: String, workspace: WorkspaceRef?): List<ComposerSkillContent> =
        emptyList()

    /** Suggestions for the active [trigger] in [scope], plus the host screen's own [hostCommands]. */
    public suspend fun suggest(
        trigger: ComposerTrigger,
        scope: ComposerScope,
        hostCommands: List<HostCommand> = emptyList(),
    ): List<ComposerSuggestion>
}
