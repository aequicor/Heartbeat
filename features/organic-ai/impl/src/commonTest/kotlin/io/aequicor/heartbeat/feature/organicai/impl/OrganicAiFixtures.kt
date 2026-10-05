package io.aequicor.heartbeat.feature.organicai.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.GrowthLimits
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.Letter
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.Work

internal val ORGANISM = OrganismId("o1")
internal val TARGET = EngineTarget(EngineId("pi"), EngineBindingId("binding"), ModelId("model"))
internal const val GOAL = "Build the thing"
internal val ZYGOTE = CellId.ZYGOTE
internal val C1 = CellId("c1")
internal val C2 = CellId("c2")

internal fun session(native: String): SessionRef = SessionRef(EngineId("pi"), SessionSourceId("local"), native)

internal fun request(cell: CellId, turn: Int = 1): RequestId =
    RequestId("organic-${ORGANISM.value}-${cell.value}-$turn")

internal fun working(cell: CellId, turn: Int = 1, work: Work = Work.Genesis, isRecovery: Boolean = false) =
    CellPhase.Working(request(cell, turn), work, isRecovery)

internal fun zygoteCell(phase: CellPhase = working(ZYGOTE), inbox: List<Letter> = emptyList()): Cell =
    Cell(ZYGOTE, "zygote", null, GOAL, phase, session("z"), inbox, turns = 1)

internal fun cell(id: CellId, parent: CellId = ZYGOTE, phase: CellPhase = working(id), session: SessionRef? = null) =
    Cell(id, "cell ${id.value}", parent, "task of ${id.value}", phase, session ?: session(id.value), turns = 1)

internal fun organism(
    vararg others: Cell,
    zygote: Cell = zygoteCell(),
    cases: List<ImmuneCase> = emptyList(),
    target: EngineTarget? = TARGET,
): Organism = Organism(
    id = ORGANISM,
    goal = GOAL,
    target = target,
    immunityTarget = null,
    workspace = null,
    trust = null,
    limits = GrowthLimits(),
    cells = listOf(zygote) + others,
    cases = cases,
    casesFiled = cases.size,
    version = 1,
)

internal fun message(role: MessageRole, text: String, turn: String? = null, position: Long = 0) = SessionItem.Message(
    ItemInfo(ItemId("m$position-$role"), position, 0, turn?.let(::TurnId)),
    role,
    listOf(ContentPart.Text(text)),
)
