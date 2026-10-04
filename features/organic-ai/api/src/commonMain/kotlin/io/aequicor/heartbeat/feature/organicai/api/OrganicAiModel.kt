package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Organic AI: engine sessions organized as an organism. The zygote session grows from the goal, cells divide by
 * their own decision and the immune system judges complaints and disputes in a fresh session per case. While off,
 * no organism is restored or developed and cells lose their hosted tools; saved organisms are kept.
 */
public val OrganicAiEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "organic_ai.enabled",
    "Органический ИИ: зигота, деление клеток-сессий и иммунитет",
)

/** Caller-generated identity of an organism; never contains native paths, accounts or credentials. */
@Serializable
public data class OrganismId(val value: String) {
    init {
        require(value.matches(SAFE_ID)) { "Invalid OrganismId" }
    }
}

/** Identity of a cell within its organism: the zygote is [CellId.ZYGOTE], divided cells `c1`, `c2`, … */
@Serializable
public data class CellId(val value: String) {
    init {
        require(value.matches(SAFE_ID)) { "Invalid CellId" }
    }

    /** Well-known ids. */
    public companion object {
        /** The progenitor of every other cell. */
        public val ZYGOTE: CellId = CellId("zygote")
    }
}

/** Identity of an immune case within its organism: `k1`, `k2`, … in filing order. */
@Serializable
public data class CaseId(val value: String) {
    init {
        require(value.matches(SAFE_ID)) { "Invalid CaseId" }
    }
}

/** Bounds of texts the organism accepts; longer input is refused, never truncated silently. */
public object OrganismBounds {
    /** Characters of the goal of an organism. */
    public const val MAX_GOAL: Int = 20_000

    /** Characters of the task of a divided cell. */
    public const val MAX_TASK: Int = 20_000

    /** Characters of a cell name. */
    public const val MAX_NAME: Int = 64

    /** Characters of a complaint reason. */
    public const val MAX_REASON: Int = 4_000

    /** Characters of a disputed question. */
    public const val MAX_QUESTION: Int = 8_000

    /** Other cells named as parties of one dispute. */
    public const val MAX_PARTIES: Int = 4

    /** Characters of a cell result kept and passed to its parent; a longer answer is cut by the host. */
    public const val MAX_RESULT: Int = 20_000
}

/**
 * Explicit growth ceilings of one organism; null is unbounded. They only refuse a division, they never kill a cell:
 * [maxCells] counts every cell the organism ever had, including the zygote and ended cells, [maxDepth] the
 * generations below the zygote.
 */
@Serializable
public data class GrowthLimits(val maxCells: Int? = null, val maxDepth: Int? = null) {
    init {
        require(maxCells == null || maxCells > 0) { "maxCells must be positive" }
        require(maxDepth == null || maxDepth > 0) { "maxDepth must be positive" }
    }
}

/**
 * Request to conceive an organism. A null [target] is resolved from the profile's default model when the zygote
 * starts; [immunityTarget] runs the judge sessions and defaults to [target]. Every cell works in [workspace] (no
 * project when null) with [trust]; actions the engine still asks about are escalated to the user.
 */
@Serializable
public data class Conception(
    val id: OrganismId,
    val goal: String,
    val target: EngineTarget? = null,
    val immunityTarget: EngineTarget? = null,
    val workspace: WorkspaceRef? = null,
    val trust: TrustLevel? = null,
    val limits: GrowthLimits = GrowthLimits(),
) {
    init {
        require(goal.isNotBlank() && goal.length <= OrganismBounds.MAX_GOAL) { "Invalid organism goal" }
    }

    override fun toString(): String = "Conception(id=${id.value}, goal=${goal.length} chars)"
}

/**
 * One organism: an immutable aggregate whose [version] grows with every durable change, so an older snapshot never
 * overwrites a newer one. Cells and cases are never removed, ended cells keep their history.
 */
@Serializable
public data class Organism(
    val id: OrganismId,
    val goal: String,
    val target: EngineTarget?,
    val immunityTarget: EngineTarget?,
    val workspace: WorkspaceRef?,
    val trust: TrustLevel?,
    val limits: GrowthLimits,
    val cells: List<Cell>,
    val cases: List<ImmuneCase> = emptyList(),
    /** Cases ever filed; the next case id is derived from it. */
    val casesFiled: Int = 0,
    val status: OrganismStatus = OrganismStatus.Developing,
    val version: Long = 0,
) {
    init {
        require(cells.count { it.parent == null } == 1) { "An organism has exactly one zygote" }
        require(cells.map { it.id }.distinct().size == cells.size) { "Duplicate cell" }
    }

    override fun toString(): String =
        "Organism(id=${id.value}, cells=${cells.size}, cases=${cases.size}, status=$status, version=$version)"
}

/** Lifecycle of an organism. Only an explicit abort kills it: the zygote is never judged or killed. */
@Serializable
public sealed interface OrganismStatus {
    /** Cells are alive. */
    @Serializable
    public data object Developing : OrganismStatus

    /** The zygote gave its final answer after every descendant ended. */
    @Serializable
    public data class Completed(val result: String) : OrganismStatus {
        override fun toString(): String = "Completed(result=${result.length} chars)"
    }

    /** Aborted from outside; every living cell was lysed. */
    @Serializable
    public data object Aborted : OrganismStatus
}

/**
 * One engine session of an organism. [parent] is null only for the zygote. [session] is set once the native
 * session exists; [inbox] keeps letters for the next turn; [turns] counts the turns ever started and makes
 * request ids unique.
 */
@Serializable
public data class Cell(
    val id: CellId,
    val name: String,
    val parent: CellId?,
    val task: String,
    val phase: CellPhase,
    val session: SessionRef? = null,
    val inbox: List<Letter> = emptyList(),
    val turns: Int = 0,
) {
    override fun toString(): String =
        "Cell(id=${id.value}, parent=${parent?.value ?: "-"}, phase=$phase, inbox=${inbox.size}, turns=$turns)"
}

/** Where a cell is in its life. Working, Resting and Stalled cells are alive. */
@Serializable
public sealed interface CellPhase {
    /**
     * A turn on [work] is under way with [request]. Without a session the cell is still germinating. A recovery
     * turn follows a restart or a resume and first adopts a native turn that is still running. [turn] and the
     * permission requests [awaiting] a user decision are observed from the engine and not saved.
     */
    @Serializable
    public data class Working(
        val request: RequestId,
        val work: Work,
        val isRecovery: Boolean = false,
        @Transient val turn: TurnId? = null,
        @Transient val awaiting: List<PermissionRequest> = emptyList(),
    ) : CellPhase {
        override fun toString(): String =
            "Working(request=${request.value}, recovery=$isRecovery, awaiting=${awaiting.size})"
    }

    /** The last turn ended while children are alive or the cell's own dispute is open; a result wakes it. */
    @Serializable
    public data object Resting : CellPhase

    /** The zygote's turn broke; it waits for an explicit resume or abort and keeps its letters. */
    @Serializable
    public data class Stalled(val breakdown: Breakdown, val work: Work) : CellPhase

    /** The final answer, delivered to the parent (for the zygote: the organism's result). */
    @Serializable
    public data class Completed(val result: String) : CellPhase {
        override fun toString(): String = "Completed(result=${result.length} chars)"
    }

    /** Ended without an answer. */
    @Serializable
    public data class Dead(val cause: DeathCause) : CellPhase
}

/** What a turn works on. */
@Serializable
public sealed interface Work {
    /** The cell's task, with its role in the organism. */
    @Serializable
    public data object Genesis : Work

    /** Letters that woke the cell or arrived during its previous turn. */
    @Serializable
    public data class Letters(val letters: List<Letter>) : Work {
        override fun toString(): String = "Letters(${letters.size})"
    }
}

/** A message the host delivers to a cell as its next prompt. */
@Serializable
public sealed interface Letter {
    /** A child gave its final answer. */
    @Serializable
    public data class ChildFinished(val child: CellId, val name: String, val result: String) : Letter {
        override fun toString(): String = "ChildFinished(child=${child.value})"
    }

    /** A child ended without an answer; its descendants ended with it. */
    @Serializable
    public data class ChildDied(val child: CellId, val name: String, val cause: DeathCause) : Letter {
        override fun toString(): String = "ChildDied(child=${child.value})"
    }

    /** The immune system answered a dispute; a null [answer] means it could not rule. */
    @Serializable
    public data class DisputeResolved(
        val case: CaseId,
        val question: String,
        val answer: String?,
        val reason: String,
    ) : Letter {
        override fun toString(): String = "DisputeResolved(case=${case.value}, answered=${answer != null})"
    }

    /** The immune system decided a complaint the cell filed. */
    @Serializable
    public data class Verdict(
        val case: CaseId,
        val accused: CellId,
        val accusedName: String,
        val outcome: VerdictOutcome,
        val reason: String,
    ) : Letter {
        override fun toString(): String = "Verdict(case=${case.value}, outcome=$outcome)"
    }
}

/** What a complaint led to. */
@Serializable
public enum class VerdictOutcome {
    /** The accused and its descendants were lysed. */
    Killed,

    /** The accused was found healthy. */
    Spared,

    /** The judge ordered a kill, but the accused had already ended. */
    Moot,

    /** The judge gave no readable verdict; the accused lives. */
    Undecided,
}

/** Why a turn could not give an answer. */
@Serializable
public sealed interface Breakdown {
    /** No model to run the organism: none was given and the profile has no default. */
    @Serializable
    public data object NoModel : Breakdown

    /** The engine failed the turn or its session. */
    @Serializable
    public data class Engine(val failure: EngineFailure) : Breakdown

    /** The turn was cancelled outside of the organism. */
    @Serializable
    public data object Interrupted : Breakdown
}

/** Why a cell ended without an answer. */
@Serializable
public sealed interface DeathCause {
    /** Killed by the immune system on [case]. */
    @Serializable
    public data class Lysed(val case: CaseId, val reason: String) : DeathCause {
        override fun toString(): String = "Lysed(case=${case.value})"
    }

    /** Its own turn broke. */
    @Serializable
    public data class Failed(val breakdown: Breakdown) : DeathCause

    /** Ended together with [ancestor]. */
    @Serializable
    public data class Orphaned(val ancestor: CellId) : DeathCause

    /** The organism was aborted. */
    @Serializable
    public data object Aborted : DeathCause
}

/** An open matter before the immune system; each one is judged by a fresh session. */
@Serializable
public sealed interface ImmuneCase {
    /** Case identity. */
    public val id: CaseId

    /** Cell that filed it. */
    public val filedBy: CellId

    /** [plaintiff] says [accused] is cancerous: harmful, looping or working against the goal. */
    @Serializable
    public data class Complaint(
        override val id: CaseId,
        val plaintiff: CellId,
        val accused: CellId,
        val reason: String,
    ) : ImmuneCase {
        init {
            require(reason.isNotBlank() && reason.length <= OrganismBounds.MAX_REASON) { "Invalid complaint" }
        }

        override val filedBy: CellId get() = plaintiff

        override fun toString(): String = "Complaint(id=${id.value}, accused=${accused.value})"
    }

    /** [asker] needs a binding answer to [question]; [parties] are other cells concerned by it. */
    @Serializable
    public data class Dispute(
        override val id: CaseId,
        val asker: CellId,
        val question: String,
        val parties: List<CellId> = emptyList(),
    ) : ImmuneCase {
        init {
            require(question.isNotBlank() && question.length <= OrganismBounds.MAX_QUESTION) { "Invalid dispute" }
        }

        override val filedBy: CellId get() = asker

        override fun toString(): String = "Dispute(id=${id.value}, parties=${parties.size})"
    }
}

/** Decision of the immune system on one case. */
@Serializable
public sealed interface Ruling {
    /** The judge's justification. */
    public val reason: String

    /** The accused is cancerous: lyse it with its descendants. */
    @Serializable
    public data class Kill(override val reason: String) : Ruling {
        override fun toString(): String = "Kill"
    }

    /** The accused is healthy. */
    @Serializable
    public data class Spare(override val reason: String) : Ruling {
        override fun toString(): String = "Spare"
    }

    /** The binding answer to a dispute. */
    @Serializable
    public data class Answer(val text: String, override val reason: String) : Ruling {
        override fun toString(): String = "Answer"
    }

    /** No readable decision; a complaint is not executed and a dispute stays unanswered. */
    @Serializable
    public data class None(override val reason: String) : Ruling {
        override fun toString(): String = "None"
    }
}

/** Why the organism refused a division, complaint or dispute; the cell is told and nothing changes. */
@Serializable
public enum class Refusal {
    /** The organism has completed or was aborted. */
    NotDeveloping,

    /** No such cell in the organism. */
    UnknownCell,

    /** The calling cell has no turn under way. */
    NotWorking,

    /** The accused has already ended. */
    CellNotAlive,

    /** The zygote is never judged. */
    ZygoteImmune,

    /** A cell cannot accuse itself. */
    SelfComplaint,

    /** The same cell already accused the same cell in an open case. */
    DuplicateComplaint,

    /** [GrowthLimits.maxCells] is reached. */
    TooManyCells,

    /** [GrowthLimits.maxDepth] is reached. */
    TooDeep,

    /** Parties repeat, include the asker or exceed [OrganismBounds.MAX_PARTIES]. */
    InvalidParties,
}

/** How a cell's session is let go. */
@Serializable
public enum class ReleaseMode {
    /** The cell completed: close the handle, keep the session. */
    Retire,

    /** The cell was killed: cancel its turn, close the handle and archive the session. */
    Lyse,
}

/** A living cell of a developing organism, as the host found it from a trusted session identity. */
public data class CellAddress(val organism: OrganismId, val cell: CellId)

/** Names of the hosted tools a cell uses; transcripts can match them. */
public object OrganismTools {
    /** Divides the calling cell: a child cell starts working on a task. */
    public const val DIVIDE: String = "organism_divide"

    /** Files a complaint about a cancerous cell. */
    public const val COMPLAIN: String = "organism_complain"

    /** Asks the immune system to settle a disputed question. */
    public const val DISPUTE: String = "organism_dispute"

    /** Shows the organism: cells, their state and open cases. */
    public const val STATUS: String = "organism_status"

    /** Argument names of the organism tools. */
    public object Arguments {
        /** Task of a new cell. */
        public const val TASK: String = "task"

        /** Short name of a new cell. */
        public const val NAME: String = "name"

        /** Id of the accused cell. */
        public const val CELL: String = "cell"

        /** Why a cell is accused. */
        public const val REASON: String = "reason"

        /** The disputed question. */
        public const val QUESTION: String = "question"

        /** Ids of other cells concerned by a dispute. */
        public const val PARTIES: String = "parties"
    }
}

private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
