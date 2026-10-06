package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import kotlin.test.Test

class OrganicAiFollowUpTest {
    private val spec = OrganicAiMachineSpec
    private val inputs = listOf(ResourceRef("attachment:follow-up", "image/png"))
    private val work = Work.FollowUp("Check the new design", inputs)
    private val command = OrganicAiIntent.Public.FollowUp(ORGANISM, work.text, work.attachments)
    private val finished = organism(
        cell(C1, phase = CellPhase.Completed("done")),
        zygote = zygoteCell(CellPhase.Completed("done"), inbox = listOf(Letter.ChildFinished(C1, "tests", "passed")))
            .copy(receivedLetters = 1),
        status = OrganismStatus.Completed("done"),
    ).copy(attachments = listOf(ResourceRef("attachment:goal", "image/png")))

    private fun living(organism: Organism) = OrganicAiState.Living(mapOf(organism.id to organism))

    private fun continued() = finished.copy(
        status = OrganismStatus.Developing,
        version = 2,
        cells = finished.cells.map {
            if (it.id == ZYGOTE) it.copy(phase = working(ZYGOTE, turn = 2, work = work), turns = 2) else it
        },
    )

    @Test
    fun `a follow-up preserves the goal, native session, descendants and read cursor`() {
        val continued = continued()
        spec.assertTransition(
            living(finished),
            command,
            living(continued),
            effects = listOf(
                OrganicAiEffect.Persist(continued),
                OrganicAiEffect.Drive(continued, ZYGOTE, request(ZYGOTE, 2)),
            ),
        )
        spec.assertIgnored(living(continued), command)
        spec.assertIgnored(
            living(continued),
            OrganicAiIntent.Internal.TurnSettled(ORGANISM, ZYGOTE, request(ZYGOTE), Settlement.Answered("old")),
        )
    }

    @Test
    fun `restart and stalled recovery keep the new message and inputs`() {
        val continued = continued()
        val recovered = continued.copy(
            version = 3,
            cells = continued.cells.map {
                if (it.id == ZYGOTE) {
                    it.copy(phase = working(ZYGOTE, turn = 3, work = work, isRecovery = true), turns = 3)
                } else {
                    it
                }
            },
        )
        spec.assertTransition(
            OrganicAiState.Awakening,
            OrganicAiIntent.Internal.Restored(listOf(continued)),
            living(recovered),
            effects = listOf(OrganicAiEffect.Revive(listOf(recovered))),
        )
        val stalled = continued.copy(
            cells = continued.cells.map {
                if (it.id == ZYGOTE) it.copy(phase = CellPhase.Stalled(Breakdown.Interrupted, work)) else it
            },
        )
        spec.assertTransition(
            living(stalled),
            OrganicAiIntent.Public.Resume(ORGANISM),
            living(recovered),
            effects = listOf(
                OrganicAiEffect.Persist(recovered),
                OrganicAiEffect.Drive(recovered, ZYGOTE, request(ZYGOTE, 3)),
            ),
        )
    }

    @Test
    fun `only completed organisms accept nonempty bounded follow-ups`() {
        spec.assertIgnored(living(finished.copy(status = OrganismStatus.Aborted)), command)
        spec.assertIgnored(living(organism()), command)
        spec.assertIgnored(OrganicAiState.Dormant, command)
        spec.assertIgnored(OrganicAiState.Living(), command)
        spec.assertIgnored(living(finished), command.copy(text = "  ", attachments = emptyList()))
        spec.assertIgnored(living(finished), command.copy(text = "x".repeat(OrganismBounds.MAX_GOAL + 1)))
        val attachmentsOnly = continued().copy(
            cells = continued().cells.map {
                if (it.id == ZYGOTE) it.copy(phase = working(ZYGOTE, turn = 2, work = work.copy(text = ""))) else it
            },
        )
        spec.assertTransition(
            living(finished),
            command.copy(text = ""),
            living(attachmentsOnly),
            effects = listOf(
                OrganicAiEffect.Persist(attachmentsOnly),
                OrganicAiEffect.Drive(attachmentsOnly, ZYGOTE, request(ZYGOTE, 2)),
            ),
        )
        val unresolved = continued().copy(target = null)
        spec.assertTransition(
            living(finished.copy(target = null)),
            command,
            living(unresolved),
            effects = listOf(OrganicAiEffect.Persist(unresolved), OrganicAiEffect.Resolve(ORGANISM)),
        )
    }
}
