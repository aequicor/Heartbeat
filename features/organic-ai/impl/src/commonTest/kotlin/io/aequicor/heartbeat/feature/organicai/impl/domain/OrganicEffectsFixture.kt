package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.message
import io.aequicor.heartbeat.feature.organicai.impl.session

/** Shared in-memory ports for the organic effect scenarios. */
internal open class OrganicEffectsFixture {
    protected val journal = Journal()
    protected val cells = Cells()
    protected val workspaces = Workspaces()
    protected val judges = Judges()
    protected val harnesses = Harnesses()
    protected val transcripts = SessionTranscripts { listOf(message(MessageRole.Assistant, "looping again")) }
    protected val effects = OrganicAiEffects(
        journal,
        { TARGET },
        cells,
        CellDriver(cells, journal, workspaces, harnesses),
        ImmunityCourt(judges, transcripts),
        workspaces,
    )
    protected val machine = Recorder()

    protected val permission = PermissionRequest(
        PermissionRequestId("p1"),
        TurnId("t1"),
        "Run",
        listOf(PermissionOption(PermissionOptionId("allow"), "Allow")),
    )

    protected class Recorder : EffectScope<OrganicAiIntent> {
        val sent = mutableListOf<OrganicAiIntent>()
        var refuse: (OrganicAiIntent) -> Boolean = { false }

        override suspend fun send(intent: OrganicAiIntent): SendResult {
            sent += intent
            return if (refuse(intent)) SendResult.Ignored else SendResult.Accepted
        }
    }

    protected class Journal : OrganismJournal {
        var stored = emptyList<Organism>()
        val saved = mutableListOf<Organism>()
        override suspend fun load(): List<Organism> = stored
        override suspend fun save(organism: Organism) {
            saved += organism
        }
    }

    protected class Workspaces : CellWorkspaces {
        val prepared = mutableListOf<Pair<Organism, CellId>>()
        val events = mutableListOf<String>()
        var failure: Exception? = null
        var beginFailure: Exception? = null
        var adoptable: TurnId? = null

        override suspend fun prepare(organism: Organism, cell: CellId): WorkspaceRef {
            failure?.let { throw it }
            prepared += organism to cell
            return WorkspaceRef("sandbox-${cell.value}")
        }

        override suspend fun begin(organism: Organism, cell: CellId, request: RequestId): CellRun {
            beginFailure?.let { throw it }
            events += "begin ${cell.value} ${request.value}"
            return Run()
        }

        override suspend fun adopt(organism: Organism, cell: CellId, session: SessionRef, turn: TurnId): CellRun? {
            if (turn != adoptable) return null
            events += "adopt ${cell.value} ${turn.value}"
            return Run()
        }

        val captured = mutableListOf<CellId>()
        var captureFailure: Exception? = null

        override suspend fun result(organism: Organism, cell: CellId): CellResult? {
            captureFailure?.let { throw it }
            captured += cell
            return CellResult("branch-${cell.value}", null, "sealed-${cell.value}")
        }

        override suspend fun branches(organism: Organism): Map<CellId, String> = emptyMap()

        inner class Run : CellRun {
            override suspend fun accepted(session: SessionRef, turn: TurnId) {
                events += "accepted ${turn.value}"
            }

            override suspend fun settled(session: SessionRef, turn: TurnId, outcome: TurnOutcome) {
                events += "settled ${turn.value} $outcome"
            }

            override suspend fun lost(session: SessionRef?, turn: TurnId?) {
                events += "lost ${session?.nativeId ?: "-"} ${turn?.value ?: "-"}"
            }
        }
    }

    protected class Harnesses : CellHarnesses {
        val connected = mutableListOf<Pair<SessionRef, Set<String>>>()
        val disconnected = mutableListOf<Pair<SessionRef, Set<String>>>()

        /** What the removal's guard answered when it was asked, in the order of removals. */
        val guards = mutableListOf<Boolean>()
        var failure: Exception? = null
        var disconnectFailure: Exception? = null
        var events: MutableList<String>? = null

        /** Runs while the connections are being made, as an ending of the cell would meanwhile. */
        var whileConnecting: () -> Unit = {}

        override suspend fun connect(session: SessionRef, harnesses: Set<String>) {
            connected += session to harnesses
            whileConnecting()
            failure?.let { throw it }
        }

        override suspend fun disconnect(
            session: SessionRef,
            harnesses: Set<String>,
            isReleased: suspend () -> Boolean,
        ) {
            events?.add("disconnect ${session.nativeId}")
            disconnected += session to harnesses
            guards += isReleased()
            disconnectFailure?.let { throw it }
        }
    }

    protected class Judges : JudgeSessions {
        var answer = ""
        val prompts = mutableListOf<Pair<EngineTarget, String>>()
        override suspend fun deliberate(
            target: EngineTarget,
            prompt: String,
            onSession: suspend (SessionRef) -> Unit,
        ): String {
            prompts += target to prompt
            onSession(session("judge-${prompts.size}"))
            return answer
        }
    }

    protected class Cells : CellSessions {
        val handle = Handle()
        var failOpen: Exception? = null
        val opened = mutableListOf<Pair<CellKey, SessionRef?>>()
        val routes = mutableListOf<CellRoute>()
        val released = mutableListOf<Released>()
        val responses = mutableListOf<Pair<CellKey, PermissionDecision>>()
        var releasedAll = 0

        /** False answers as a stale retirement does: nothing was let go. */
        var isReleased = true

        override suspend fun open(key: CellKey, route: CellRoute, existing: SessionRef?, generation: Int): CellHandle {
            failOpen?.let { throw it }
            opened += key to existing
            routes += route
            return handle
        }

        override suspend fun respond(key: CellKey, decision: PermissionDecision) {
            responses += key to decision
        }

        /** What lysing a cell proves; a cell listed in [unstoppable] never stops. */
        val unstoppable = mutableSetOf<CellId>()

        override suspend fun release(
            key: CellKey,
            session: SessionRef?,
            mode: ReleaseMode,
            generation: Int,
        ): ReleaseOutcome {
            events?.add("release ${key.cell.value}")
            released += Triple(key, session, mode)
            return if (!isReleased || key.cell in unstoppable) ReleaseOutcome.Unconfirmed else ReleaseOutcome.Stopped
        }

        /** Cells that were lysed, as the real sessions would remember. */
        val lysed = mutableSetOf<CellKey>()

        /** The answer to [isReleased] by cell; a cell not listed is let go. */
        val letGo = mutableMapOf<CellKey, Boolean>()
        var events: MutableList<String>? = null

        override suspend fun isLysed(key: CellKey): Boolean = key in lysed

        /** The generations the removals asked about, by cell. */
        val asked = mutableListOf<Pair<CellKey, Int>>()

        override suspend fun isReleased(key: CellKey, generation: Int): Boolean {
            asked += key to generation
            return letGo[key] ?: true
        }

        override suspend fun hasPendingRelease(organism: OrganismId) = false

        override suspend fun releaseAll() {
            releasedAll++
        }
    }

    protected class Handle : CellHandle {
        override val session: SessionRef = session("new")
        var active: TurnId? = null
        var pending = emptyList<PermissionRequest>()
        var outcome: TurnOutcome = TurnOutcome.Completed
        var answer: String? = "the answer"
        var onSubmit: () -> Unit = {}
        var onAwait: () -> Unit = {}
        val submitted = mutableListOf<Pair<RequestId, String>>()
        val cancelled = mutableListOf<TurnId>()
        val inputs = mutableListOf<List<ResourceRef>>()

        override fun activeTurn(): TurnId? = active

        override suspend fun submit(
            request: RequestId,
            text: String,
            trust: TrustLevel?,
            attachments: List<ResourceRef>,
        ): TurnId {
            onSubmit()
            submitted += request to text
            inputs += attachments
            return TurnId("t1")
        }

        override suspend fun cancel(turn: TurnId) {
            cancelled += turn
        }

        override suspend fun await(turn: TurnId, onPending: suspend (List<PermissionRequest>) -> Unit): TurnOutcome {
            onAwait()
            if (pending.isNotEmpty()) onPending(pending)
            return outcome
        }

        val unconfirmed = mutableListOf<Boolean>()

        override suspend fun answer(turn: TurnId, isUnconfirmed: Boolean): String? = answer.also {
            unconfirmed += isUnconfirmed
        }
    }
}

internal typealias Released = Triple<CellKey, SessionRef?, ReleaseMode>
