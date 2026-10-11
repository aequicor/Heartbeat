package io.aequicor.heartbeat.feature.autocomplete.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Composer context of one pane: engine route, effective workspace and input limits of its model. */
public data class ComposerScope(
    public val target: EngineTarget? = null,
    public val workspace: WorkspaceRef? = null,
    public val inputSupport: PromptInputSupport? = null,
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

    /** `/` at the start of a line; an empty query still lists every available command. */
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
 * Main-safe suggestion service of one profile. Suggest never starts processes on its own, ranks the merged
 * result with Heartbeat items before native engine ones, and returns an empty list to hide the popup.
 */
public interface ComposerAssists {
    /** Suggestions for the active [trigger] in [scope], plus the host screen's own [hostCommands]. */
    public suspend fun suggest(
        trigger: ComposerTrigger,
        scope: ComposerScope,
        hostCommands: List<HostCommand> = emptyList(),
    ): List<ComposerSuggestion>
}
