package io.aequicor.heartbeat.feature.aiengine.facade.api

/**
 * A native command or skill an engine offers to the composer of a chat it would run. [insert] is the text the
 * composer places instead of the typed token, following the engine's own convention (for example a slash
 * command with a trailing space); adapters own its exact form, the host never rewrites it.
 */
public data class EngineAssist(
    public val id: String,
    public val label: String,
    public val description: String = "",
    public val insert: String,
    public val kind: Kind,
) {
    /** What the assist completes in the composer. */
    public enum class Kind { Command, Skill }

    init {
        require(id.isNotBlank()) { "Empty EngineAssist id" }
        require(label.isNotBlank()) { "Empty EngineAssist label" }
        require(insert.isNotBlank()) { "Empty EngineAssist insert" }
    }
}

/**
 * Engine-wide native assists of the composer. Obtaining the feature never starts a session or a turn: the
 * route authorizes the listing exactly like a read-only catalog, and adapters cache their native discovery
 * themselves. An engine without native commands and skills does not implement the contract; missing support
 * is not a confirmed empty list.
 */
public interface ListsComposerAssists : EngineFeature {
    /** Native assists visible on [target] and [workspace]; a null workspace is a chat without a project. */
    public suspend fun assists(target: EngineTarget, workspace: WorkspaceRef?): List<EngineAssist>

    /** Typed composer-assist key. */
    public companion object : EngineFeatureKey<ListsComposerAssists>(
        EngineFeatureId("engine.assists"),
        ListsComposerAssists::class,
    )
}
