package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
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
internal data class CellRoute(val target: EngineTarget, val workspace: WorkspaceRef?, val trust: TrustLevel?)

/** Engine sessions of cells; one handle per cell is kept open while the cell lives. */
internal interface CellSessions {
    /**
     * The open handle of [key]: the cached one, [existing] resumed, or a new session. Cells get the detached hosted
     * tools, since the organism shows their permission requests to the user. A lysed cell is never opened again.
     */
    suspend fun open(key: CellKey, route: CellRoute, existing: SessionRef?): CellHandle

    /** Delivers the user's [decision] to the open handle of [key]. */
    suspend fun respond(key: CellKey, decision: PermissionDecision)

    /**
     * Lets the session of [key] go. [ReleaseMode.Lyse] cancels its turn, closes it and archives the session;
     * failures are logged, never thrown, so one cell cannot keep others alive.
     */
    suspend fun release(key: CellKey, session: SessionRef?, mode: ReleaseMode)

    /** Cancels every running turn and closes every handle without archiving, before sleep. */
    suspend fun releaseAll()
}

/** One open cell session. */
internal interface CellHandle {
    /** The native session. */
    val session: SessionRef

    /** A turn the engine is still running, such as one accepted before a restart. */
    fun activeTurn(): TurnId?

    /** Submits [text] as [request] with [trust] when the session applies trust levels; returns the accepted turn. */
    suspend fun submit(request: RequestId, text: String, trust: TrustLevel?): TurnId

    /** Best-effort cancellation of [turn]. */
    suspend fun cancel(turn: TurnId)

    /** Waits for [turn] to end, reporting the permission requests that await the user whenever they change. */
    suspend fun await(turn: TurnId, onPending: suspend (List<PermissionRequest>) -> Unit): TurnOutcome

    /** The final answer of [turn] from the session history, or null when it has none; throws when unreadable. */
    suspend fun answer(turn: TurnId): String?
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
