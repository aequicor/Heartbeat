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
     * [generation] or an older open handle; lysis always ends the cell. False when a retirement was stale and let
     * nothing go, so the cell's session is still in use.
     */
    suspend fun release(key: CellKey, session: SessionRef?, mode: ReleaseMode, generation: Int = 0): Boolean

    /** Whether the cell [key] was lysed: it is never opened again, and nothing may be connected to its session. */
    suspend fun isLysed(key: CellKey): Boolean

    /**
     * Whether the session of [key] is let go as of [generation]: the cell was lysed, or no later turn opened it
     * (a turn raises the generation as it starts to open the cell) and it has no open handle. A removal of its
     * resources runs only while this holds.
     */
    suspend fun isReleased(key: CellKey, generation: Int): Boolean

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
