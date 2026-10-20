package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState

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
    suspend fun submit(intent: HarnessIntent.Command): LibraryOutcome
}

/** Static tool metadata for policy authoring; reads never start an engine or a session. */
internal interface HarnessToolCatalogs {
    /** Heartbeat tool groups, including currently unavailable tools. */
    fun hosted(): List<ToolGroup>

    /** Registered, currently enabled engines in catalog order. */
    fun engines(): List<EngineDescriptor>

    /** Effective native policy for a trusted scope. */
    suspend fun effective(scope: ToolPolicyScope): ResolvedToolPolicy
}
