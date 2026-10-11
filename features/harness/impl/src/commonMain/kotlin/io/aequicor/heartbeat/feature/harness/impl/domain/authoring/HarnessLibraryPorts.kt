package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import kotlinx.coroutines.flow.Flow

/** Durable result of one library command, correlated only by its own request id. */
internal sealed interface LibraryOutcome {
    /** The exact command committed; [output] is its success receipt. */
    data class Committed(val output: HarnessOutput) : LibraryOutcome

    /** The machine refused the command without writing. */
    data class Rejected(val reason: HarnessRejection) : LibraryOutcome

    /** The write failed and the previous record stays effective. */
    data object StorageFailed : LibraryOutcome

    /** The machine did not take the command. */
    data object NotTaken : LibraryOutcome

    /** Taken but not answered in time; it may still commit, so a retry must read the library first. */
    data object Unconfirmed : LibraryOutcome
}

/** Agent and UI side of the library machine: committed snapshots and correlated commands. */
internal interface HarnessLibraryClient {
    /** Current committed snapshot without waiting; null while loading, failed or suspended. */
    val current: HarnessState.Ready?

    /** Waits briefly for the restored library. */
    suspend fun ready(): HarnessState.Ready?

    /** Sends one command and waits for its own receipt. */
    suspend fun submit(intent: HarnessIntent.Public): LibraryOutcome

    /** Source project of a session workspace: a worktree maps to its project; unknown folders have none. */
    suspend fun sourceProject(workspace: WorkspaceRef?): WorkspaceRef?
}

/** Static tool metadata for policy authoring; reads never start an engine or a session. */
internal interface HarnessToolCatalogs {
    /** Heartbeat tool groups, including currently unavailable tools. */
    fun hosted(): List<ToolGroup>

    /** Registered, currently enabled engines in catalog order. */
    fun engines(): List<EngineDescriptor>

    /** Effective native policy for a trusted scope. */
    suspend fun effective(scope: ToolPolicyScope): ResolvedToolPolicy

    /** Whether gated native tools may be switched on: the `harness.native_tools` toggle. */
    fun nativeEnabling(): Flow<Boolean>

    /**
     * Checks the changes from [current] to [next] against the catalogs; null means valid. Unchanged entries are not
     * checked again, so switches of an engine that is unavailable now never block other edits.
     */
    fun problem(current: ToolPolicySpec, next: ToolPolicySpec): String? =
        toolPolicyProblem(next, hosted(), engines(), current)
}

/**
 * Harness tools stay available so that a harness cannot lock the agent out of its own repair. Only gated native
 * tools can be switched on; a native name colliding with a Heartbeat tool must use hosted_off. Only entries that
 * differ from [current] are checked: removing a switch never needs the catalog.
 */
internal fun toolPolicyProblem(
    spec: ToolPolicySpec,
    groups: List<ToolGroup>,
    engines: List<EngineDescriptor>,
    current: ToolPolicySpec = ToolPolicySpec(),
): String? {
    val hosted = groups.flatMap { it.tools }.map { it.name }.toSet()
    val added = spec.hostedOff - current.hostedOff
    val unknown = added.filter { it !in hosted }
    val own = added.filter { it.startsWith("harness_") || it.startsWith(HarnessTools.SCRIPT_PREFIX) }
    val registered = engines.associateBy { it.id.value }
    return when {
        own.isNotEmpty() -> "harness tools cannot be turned off by a harness"

        unknown.isNotEmpty() -> "unknown Heartbeat tools: ${unknown.joinToString()}; see harness_tools_catalog"

        else -> spec.native.entries.firstNotNullOfOrNull { (engine, switches) ->
            val changed = switches.filter { (tool, switch) -> current.native[engine]?.get(tool) != switch }
            if (changed.isEmpty()) return@firstNotNullOfOrNull null
            val descriptor = registered[engine] ?: return@firstNotNullOfOrNull "unknown or disabled engine $engine"
            val native = descriptor.nativeTools.associateBy { it.name }
            changed.entries.firstNotNullOfOrNull { (tool, switch) ->
                val known = native[tool]
                when {
                    known == null -> "$engine has no native tool $tool"
                    tool in hosted -> "$tool is also a Heartbeat tool; use hosted_off"
                    switch == ToolSwitch.On && !known.isGated -> "$engine tool $tool can only be turned off"
                    else -> null
                }
            }
        }
    }
}
