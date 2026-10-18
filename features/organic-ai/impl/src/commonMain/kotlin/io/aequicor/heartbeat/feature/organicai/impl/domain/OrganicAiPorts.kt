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
    /** The organism's harness choice ([Organism.harnesses]); the session is opened with it, never a subset. */
    val harnesses: Set<String> = emptySet(),
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
     * as one cut short by a restart, is closed first. The caller must have found no live native turn to adopt.
     * Fails when the sandbox cannot take the run.
     */
    suspend fun begin(organism: Organism, cell: CellId, request: RequestId): CellRun

    /**
     * Takes over the run of the native [turn] of [session] that survived a restart and is still running, or null when
     * the sandbox holds no run of exactly that turn that can be taken over.
     */
    suspend fun adopt(organism: Organism, cell: CellId, session: SessionRef, turn: TurnId): CellRun?

    /**
     * What an ended [cell] leaves: its sandbox branch and an immutable commit capturing all files of its checkout,
     * committed or not, taken once per completed run and invalidated when the cell starts another run.
     * Null when worktree mode cannot capture it now.
     */
    suspend fun result(organism: Organism, cell: CellId): CellResult?

    /** The branch of each cell's sandbox that worktree mode knows. */
    suspend fun branches(organism: Organism): Map<CellId, String>
    suspend fun isPaused(organism: Organism, cell: CellId): Boolean = false
    suspend fun awaitAvailable(organism: Organism, cell: CellId) = Unit
}

/**
 * The work of a finished sandbox cell: its [branch], cut from [base], and the [commit] that holds every file of its
 * checkout on top of that branch. A parent integrates the commit into its own checkout with git.
 */
internal data class CellResult(
    val branch: String,
    val base: String?,
    val commit: String,
    val virtualWorkspace: WorkspaceRef? = null,
)

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

/**
 * Connects the harnesses of an organism to the native session of one of its cells, and lets them go when the cell
 * ends. A cell's turn follows only a complete connection, so a harness that cannot be connected stops the turn
 * instead of running it without the environment the user chose.
 */
internal interface CellHarnesses {
    /**
     * Returns once every one of [harnesses] is durably connected to [session]; a connection already there is not
     * written again, and a library that is still loading or busy is awaited for a while. Throws when any cannot be
     * connected (the library is off or unavailable, the harness is gone or disabled, a write was refused or not
     * confirmed). Without [harnesses] it does nothing, even with the harness feature off.
     */
    suspend fun connect(session: SessionRef, harnesses: Set<String>)

    /**
     * Removes the connections of [harnesses] to [session], whose cell has ended, so dead sessions never fill the
     * connection quota of a harness. A cell that wakes again is connected anew before its next prompt; so that a late
     * removal never strips a session a newer turn already uses, [isReleased] is asked again right before each write
     * and a removal is skipped once it says no. Each harness gets fifteen seconds. Best effort: throws when any
     * connection stays (a failed or cancelled removal is not retried later); a connection not there is not written.
     * Does nothing without [harnesses] or with the harness feature off.
     */
    suspend fun disconnect(session: SessionRef, harnesses: Set<String>, isReleased: suspend () -> Boolean = { true })
}

/** Engine sessions of cells; one handle per cell is kept open while the cell lives. */
internal interface CellSessions {
    /**
     * The open handle of [key]: the cached one, [existing] resumed, or a new session. Cells get the detached hosted
     * tools, since the organism shows their permission requests to the user. A lysed cell is never opened again.
     * A new session declares the [CellRoute.harnesses] from its start and a cell with harnesses opts into session
     * hooks; connecting the harnesses is the caller's step ([CellHarnesses]), as it needs the bound session.
     * [generation] is the cell turn counter: a late retirement of an older turn cannot close this handle.
     */
    suspend fun open(key: CellKey, route: CellRoute, existing: SessionRef?, generation: Int = 0): CellHandle

    /** Delivers the user's [decision] to the open handle of [key]. */
    suspend fun respond(key: CellKey, decision: PermissionDecision)

    /**
     * Lets the session of [key] go. [ReleaseMode.Lyse] cancels its turn, closes it and archives the session;
     * failures are logged, never thrown, so one cell cannot keep others alive. Retirement applies only to
     * [generation] or an older open handle; lysis always ends the cell.
     *
     * Lysis returns [ReleaseOutcome.Stopped] only when every handle this process held for the cell had its turn seen
     * to stop and no opener of the cell is still running; a turn that did not stop keeps its handle open for the
     * next lysis of the same cell. A cell this process holds no handle of counts as stopped.
     */
    suspend fun release(key: CellKey, session: SessionRef?, mode: ReleaseMode, generation: Int = 0): ReleaseOutcome

    /** Whether the cell [key] was lysed: it is never opened again, and nothing may be connected to its session. */
    suspend fun isLysed(key: CellKey): Boolean

    /**
     * Whether the session of [key] is let go as of [generation]: it has no held handle or pending opener, and
     * either the cell was lysed or no later turn opened it (opening raises its generation). An unconfirmed lysis
     * keeps resources connected until its handles are released. A removal runs only while this holds.
     */
    suspend fun isReleased(key: CellKey, generation: Int): Boolean

    /** Whether this profile still holds a handle or an unfinished opener for [organism], even across sleep. */
    suspend fun hasPendingRelease(organism: OrganismId): Boolean

    /** Stops all turns before sleep, retaining unconfirmed handles for recovery or a later lysis. */
    suspend fun releaseAll()
}

/** What a release could prove about the native work of a cell. */
internal enum class ReleaseOutcome {
    /** The cell's turn stopped and its handle closed, or this process held none. */
    Stopped,

    /** A turn did not stop in time, its session was unreachable or the cell was still being opened. */
    Unconfirmed,
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
