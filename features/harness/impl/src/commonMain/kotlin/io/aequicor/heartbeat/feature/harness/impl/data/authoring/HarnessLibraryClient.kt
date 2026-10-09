package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.sourceProjectOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

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

@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineHarnessLibraryClient(
    private val machine: Lazy<HarnessMachine>,
    private val machines: MachineRegistry,
    private val workspaces: LocalWorkspaces,
) : HarnessLibraryClient {
    private val log = Log.tag("HarnessTools")

    override val current: HarnessState.Ready?
        get() = (machine.value.state.value as? HarnessState.Ready)?.takeUnless { it.isSuspended }

    override suspend fun ready(): HarnessState.Ready? {
        log.v { "Await the restored harness library" }
        return withTimeoutOrNull(LOAD_TIMEOUT) {
            machine.value.state.first { it is HarnessState.Ready || it is HarnessState.Failed }
        }.let { (it as? HarnessState.Ready)?.takeUnless(HarnessState.Ready::isSuspended) }
    }

    override suspend fun submit(intent: HarnessIntent.Public): LibraryOutcome = coroutineScope {
        log.d { "Submit harness library command ${intent::class.simpleName}" }
        val library = machine.value
        val outcome = async(start = CoroutineStart.UNDISPATCHED) {
            library.outputs.mapNotNull { it.outcomeOf(intent) }.first()
        }
        try {
            if (library.send(intent) != SendResult.Accepted) {
                LibraryOutcome.NotTaken
            } else {
                withTimeoutOrNull(OUTCOME_TIMEOUT) { outcome.await() } ?: LibraryOutcome.Unconfirmed
            }
        } finally {
            outcome.cancel()
        }
    }

    override suspend fun sourceProject(workspace: WorkspaceRef?): WorkspaceRef? {
        if (workspace == null) return null
        log.v { "Resolve the source project of a session workspace" }
        val worktrees = machines.find(WorktreeMachineKey)?.state?.value as? WorktreeState.Ready
        return worktrees?.sourceProjectOf(workspace) ?: workspace.takeIf { isSavedProject(it) }
    }

    private suspend fun isSavedProject(workspace: WorkspaceRef): Boolean = workspaces.isAvailable &&
        withTimeoutOrNull(LOAD_TIMEOUT) { workspaces.observe().first() }.orEmpty().any { it.ref == workspace }
}

private fun HarnessOutput.outcomeOf(intent: HarnessIntent.Public): LibraryOutcome? {
    val request = when (this) {
        is HarnessOutput.Created -> requestId
        is HarnessOutput.Updated -> requestId
        is HarnessOutput.Deleted -> requestId
        is HarnessOutput.Attached -> requestId
        is HarnessOutput.Detached -> requestId
        is HarnessOutput.ApprovalChanged -> requestId
        is HarnessOutput.Rejected -> requestId
        is HarnessOutput.StorageFailed -> requestId
        else -> null
    }
    return when {
        request != intent.requestId -> null
        this is HarnessOutput.Rejected -> LibraryOutcome.Rejected(reason)
        this is HarnessOutput.StorageFailed -> LibraryOutcome.StorageFailed
        else -> LibraryOutcome.Committed(this)
    }
}

/** Fixed explanations for the model; no content or storage text. */
internal fun LibraryOutcome.failureText(): String? = when (this) {
    is LibraryOutcome.Committed -> null

    is LibraryOutcome.Rejected -> when (reason) {
        HarnessRejection.Unavailable -> "the harness library is unavailable"
        HarnessRejection.Conflict -> "the harness changed meanwhile; read it with harness_get and retry"
        HarnessRejection.Busy -> "another change of this harness is being saved; retry shortly"
        HarnessRejection.NotFound -> "no such harness or item"
        HarnessRejection.Duplicate -> "a harness or item with this name already exists"
        HarnessRejection.Limit -> "a library limit is reached (harnesses, items, size or connected chats)"
        HarnessRejection.Invalid -> "the change is invalid"
    }

    LibraryOutcome.StorageFailed -> "the library could not be saved; nothing changed"

    LibraryOutcome.NotTaken -> "the library did not take the request; nothing changed"

    LibraryOutcome.Unconfirmed ->
        "not confirmed yet: the library is still saving; check with harness_get before retrying"
}

private val LOAD_TIMEOUT = 3.seconds
private val OUTCOME_TIMEOUT = 10.seconds
