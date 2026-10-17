package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismSession
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.sandboxTask

/** The running organic AI machine of the profile. */
internal typealias OrganicAiMachine = Machine<OrganicAiState, OrganicAiIntent, OrganicAiOutput>

/** Durable organisms of the profile. Texts of organisms are never logged. */
internal interface OrganismJournal {
    /** Every readable organism. An unreadable record is skipped and kept; an unreadable journal throws. */
    suspend fun load(): List<Organism>

    /** Writes [organism] unless the journal holds the same or a newer version of it. */
    suspend fun save(organism: Organism)
}

/** The profile's default model, used for an organism conceived without one. */
internal fun interface DefaultTargets {
    /** The selected default route, or null when the user has none. */
    suspend fun default(): EngineTarget?
}

/** A living cell of a developing organism, as the host found it from a trusted session identity. */
internal data class CellAddress(val organism: OrganismId, val cell: CellId)

/** Address of the session of one cell. */
internal data class CellKey(val organism: OrganismId, val cell: CellId)

/** How the sessions of an organism run. */
internal data class CellRoute(
    val target: EngineTarget,
    val workspace: WorkspaceRef?,
    val trust: TrustLevel?,
    /** The cell's own effort; null leaves the model's default. */
    val reasoningEffort: String? = null,
    /** Reviews the cell's automatic safety decisions when it runs a cheaper model than the organism. */
    val reviewTarget: EngineTarget? = null,
)

/** Own checkouts of the cells of sandbox organisms; worktree mode owns the checkouts, their branches and runs. */
internal interface CellWorkspaces {
    /**
     * The sandbox of [cell], cut from the organism's project under [sandboxTask]: prepared on first use and found
     * again, never cut twice, after a restart or a retry. Fails when it cannot be prepared; a cell never falls back to
     * working in the project itself.
     */
    suspend fun prepare(organism: Organism, cell: CellId): WorkspaceRef

    /**
     * Registers the turn of [request] with the sandbox of [cell] before anything is submitted, so the hosted build
     * tools of the sandbox act only for this turn's session. A run the cell left unsettled by an older request, such
     * as one cut short by a restart, is closed first. Fails when the sandbox cannot take the run.
     */
    suspend fun begin(organism: Organism, cell: CellId, request: RequestId): CellRun

    /**
     * Takes over the run of the native [turn] of [session] that survived a restart and is still running, or null when
     * the sandbox holds no run of exactly that turn that can be taken over.
     */
    suspend fun adopt(organism: Organism, cell: CellId, session: SessionRef, turn: TurnId): CellRun?

    /**
     * What an ended [cell] leaves: its sandbox branch and an immutable commit capturing all files of its checkout,
     * committed or not, taken once and reused afterwards. Null when worktree mode cannot capture it now.
     */
    suspend fun result(organism: Organism, cell: CellId): CellResult?

    /** The branch of each cell's sandbox that worktree mode knows. */
    suspend fun branches(organism: Organism): Map<CellId, String>
}

/**
 * The work of a finished sandbox cell: its [branch], cut from [base], and the [commit] that holds every file of its
 * checkout on top of that branch. A parent integrates the commit into its own checkout with git.
 */
internal data class CellResult(val branch: String, val base: String?, val commit: String)

/**
 * The run of one turn of a sandbox cell in worktree mode. Reporting never fails the turn: a report that is lost leaves
 * the run unsettled, which the next [CellWorkspaces.begin] of the cell closes.
 */
internal interface CellRun {
    /** The engine accepted the turn as [turn] of [session]. */
    suspend fun accepted(session: SessionRef, turn: TurnId)

    /** The turn ended with [outcome]; running builds of a cancelled turn are cancelled. */
    suspend fun settled(session: SessionRef, turn: TurnId, outcome: TurnOutcome)

    /**
     * The turn could not be started or followed to its end. Ownership stays with the run, whose outcome is unknown,
     * and it is never reported as finished: only a later run of the cell closes it.
     */
    suspend fun lost(session: SessionRef?, turn: TurnId?)

    /** The run of a cell that shares the organism's project: there is nothing to register. */
    companion object None : CellRun {
        override suspend fun accepted(session: SessionRef, turn: TurnId) = Unit

        override suspend fun settled(session: SessionRef, turn: TurnId, outcome: TurnOutcome) = Unit

        override suspend fun lost(session: SessionRef?, turn: TurnId?) = Unit
    }
}

/** Engine sessions of cells; one handle per cell is kept open while the cell lives. */
internal interface CellSessions {
    /**
     * The open handle of [key]: the cached one, [existing] resumed, or a new session. Cells get the detached hosted
     * tools, since the organism shows their permission requests to the user. A lysed cell is never opened again.
     * [generation] is the cell turn counter: a late retirement of an older turn cannot close this handle.
     */
    suspend fun open(key: CellKey, route: CellRoute, existing: SessionRef?, generation: Int = 0): CellHandle

    /** Delivers the user's [decision] to the open handle of [key]. */
    suspend fun respond(key: CellKey, decision: PermissionDecision)

    /**
     * Lets the session of [key] go. [ReleaseMode.Lyse] cancels its turn, closes it and archives the session;
     * failures are logged, never thrown, so one cell cannot keep others alive. Retirement applies only to
     * [generation] or an older open handle; lysis always ends the cell.
     */
    suspend fun release(key: CellKey, session: SessionRef?, mode: ReleaseMode, generation: Int = 0)

    /** Cancels every running turn and closes every handle without archiving, before sleep. */
    suspend fun releaseAll()
}

/** One open cell session. */
internal interface CellHandle {
    /** The native session. */
    val session: SessionRef

    /** A turn the engine is still running, such as one accepted before a restart. */
    fun activeTurn(): TurnId?

    /** Submits the text and profile-owned inputs with trust when supported; returns the accepted turn. */
    suspend fun submit(
        request: RequestId,
        text: String,
        trust: TrustLevel?,
        attachments: List<ResourceRef> = emptyList(),
    ): TurnId

    /** Best-effort cancellation of [turn]. */
    suspend fun cancel(turn: TurnId)

    /** Waits for [turn] to end, reporting the permission requests that await the user whenever they change. */
    suspend fun await(turn: TurnId, onPending: suspend (List<PermissionRequest>) -> Unit): TurnOutcome

    /**
     * The final answer of [turn] from the session history, or null when it has none; throws when unreadable. When
     * the end of the turn is [isUnconfirmed] and the handle followed it only because the session remembered it after
     * an ambiguous delivery, only an answer history marks as [turn]'s counts: its prompt may never have reached the
     * engine, and the answer after the last prompt would then be an earlier turn's.
     */
    suspend fun answer(turn: TurnId, isUnconfirmed: Boolean = false): String?
}

/** Judge sessions: each call is a new session with no tools and one turn, released afterwards. */
internal fun interface JudgeSessions {
    /** The judge's whole answer to [prompt]; [onSession] learns the new session before the turn starts. */
    suspend fun deliberate(target: EngineTarget, prompt: String, onSession: suspend (SessionRef) -> Unit): String
}

/** Recent history of a cell session, for the judge's dossier. */
internal fun interface SessionTranscripts {
    /** The newest items of [session], reopened as the organism opened it when only an open session has history. */
    suspend fun recent(session: OrganismSession): List<SessionItem>
}
