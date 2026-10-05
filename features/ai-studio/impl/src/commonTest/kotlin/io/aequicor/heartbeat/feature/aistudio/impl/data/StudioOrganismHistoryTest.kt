package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.GrowthLimits
import io.aequicor.heartbeat.feature.organicai.api.ImmuneCase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.Ruling
import io.aequicor.heartbeat.feature.organicai.api.Trial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class StudioOrganismHistoryTest {
    private val target = EngineTarget(EngineId("codex"), EngineBindingId("b"), ModelId("model"))
    private val ref = SessionRef(target.engine, SessionSourceId("source"), "zygote")
    private val organism = Organism(
        OrganismId("organism"),
        "Task",
        target,
        immunityTarget = null,
        workspace = null,
        trust = null,
        limits = GrowthLimits(),
        cells = listOf(Cell(CellId.ZYGOTE, "Zygote", null, "Task", CellPhase.Resting, ref)),
    )

    @Test
    fun `recording follows unseen living cells and finishes before their final snapshot`() = runTest {
        val states = MutableStateFlow<OrganicAiState.Living?>(state(organism))
        val actions = mutableListOf<String>()
        backgroundScope.launch {
            recordOrganismHistory(states) { session, isLive ->
                actions += "start:${session.ref.nativeId}:$isLive"
                if (isLive) {
                    try {
                        awaitCancellation()
                    } finally {
                        actions += "flush:${session.ref.nativeId}"
                    }
                }
            }
        }
        runCurrent()
        states.value = state(organism.copy(version = 2))
        runCurrent()
        assertEquals(listOf("start:zygote:true"), actions)

        states.value = state(organism.copy(cells = organism.cells.map { it.copy(phase = CellPhase.Completed("Done")) }))
        runCurrent()
        assertEquals(listOf("start:zygote:true", "flush:zygote", "start:zygote:false"), actions)
        states.value = null
        runCurrent()
    }

    @Test
    fun `a stopped live reader retries without a state change and removal cancels recovery`() = runTest {
        val states = MutableStateFlow<OrganicAiState.Living?>(state(organism))
        var attempts = 0
        var cancellations = 0
        backgroundScope.launch {
            recordOrganismHistory(states) { _, isLive ->
                assertEquals(true, isLive)
                attempts++
                // The viewer handles and logs transient engine failures, then returns to its caller.
                if (attempts > 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancellations++
                    }
                }
            }
        }
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(0, cancellations)

        states.value = null
        runCurrent()
        assertEquals(1, cancellations)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, attempts)
    }

    @Test
    fun `existing ended cells stay dormant but a newly noticed finished judge is saved once`() = runTest {
        val ended = organism.copy(cells = organism.cells.map { it.copy(phase = CellPhase.Completed("Done")) })
        val states = MutableStateFlow<OrganicAiState.Living?>(state(ended))
        val recorded = mutableListOf<String>()
        backgroundScope.launch {
            recordOrganismHistory(states) { session, isLive ->
                recorded += session.ref.nativeId
                assertFalse(isLive)
                assertNull(session.reopening.workspace)
                assertFalse(session.reopening.areDetachedToolsEnabled)
            }
        }
        runCurrent()
        assertEquals(emptyList(), recorded)
        val trial = Trial(
            ImmuneCase.Dispute(CaseId("k1"), CellId.ZYGOTE, "Choose"),
            ref.copy(nativeId = "judge"),
            Ruling.Answer("A", "Reason"),
        )
        states.value = state(ended.copy(trials = listOf(trial)))
        runCurrent()
        states.value = state(ended.copy(trials = listOf(trial), version = 3))
        runCurrent()
        assertEquals(listOf("judge"), recorded)
    }

    private fun state(organism: Organism) = OrganicAiState.Living(mapOf(organism.id to organism))
}
