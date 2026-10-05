package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId

internal val ORGANISM = OrganismId("o1")
internal val TARGET = EngineTarget(EngineId("pi"), EngineBindingId("binding"), ModelId("model"))
internal const val GOAL = "Build the thing"
internal val ZYGOTE = CellId.ZYGOTE
internal val C1 = CellId("c1")
internal val C2 = CellId("c2")
internal val C3 = CellId("c3")

internal fun session(native: String): SessionRef = SessionRef(EngineId("pi"), SessionSourceId("local"), native)

internal fun request(cell: CellId, turn: Int = 1): RequestId = RequestId(
    "organic-${ORGANISM.value}-${cell.value}-$turn",
)

internal fun working(cell: CellId, turn: Int = 1, work: Work = Work.Genesis, isRecovery: Boolean = false) =
    CellPhase.Working(request(cell, turn), work, isRecovery)

internal fun zygoteCell(phase: CellPhase = working(ZYGOTE), turns: Int = 1, inbox: List<Letter> = emptyList()): Cell =
    Cell(ZYGOTE, "zygote", null, GOAL, phase, session("z"), inbox, turns)

internal fun cell(
    id: CellId,
    parent: CellId = ZYGOTE,
    phase: CellPhase = working(id),
    turns: Int = 1,
    inbox: List<Letter> = emptyList(),
): Cell = Cell(id, "cell ${id.value}", parent, "task of ${id.value}", phase, session(id.value), inbox, turns)

internal fun organism(
    vararg others: Cell,
    zygote: Cell = zygoteCell(),
    cases: List<ImmuneCase> = emptyList(),
    casesFiled: Int = cases.size,
    limits: GrowthLimits = GrowthLimits(),
    status: OrganismStatus = OrganismStatus.Developing,
    version: Long = 1,
    target: EngineTarget? = TARGET,
): Organism = Organism(
    id = ORGANISM,
    goal = GOAL,
    target = target,
    immunityTarget = null,
    workspace = null,
    trust = null,
    limits = limits,
    cells = listOf(zygote) + others,
    cases = cases,
    casesFiled = casesFiled,
    status = status,
    version = version,
)
