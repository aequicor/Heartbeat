package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletedOrganismReleaseTest {
    private val spec = OrganicAiMachineSpec

    private fun living(organism: Organism) = OrganicAiState.Living(mapOf(organism.id to organism))

    @Test
    fun `an answer waits for every cell including one killed earlier and retries without losing the answer`() {
        val before = organism(cell(C1, phase = CellPhase.Dead(DeathCause.Aborted)))
        val answered = before.copy(
            cells = before.cells.map { if (it.id == ZYGOTE) it.copy(phase = CellPhase.Completed("done")) else it },
            status = OrganismStatus.Completed("done"),
            sessions = SessionRelease.Releasing,
            version = before.version + 1,
        )
        spec.assertTransition(
            living(before),
            OrganicAiIntent.Internal.TurnSettled(ORGANISM, ZYGOTE, request(ZYGOTE), Settlement.Answered("done")),
            living(answered),
            effects = listOf(
                OrganicAiEffect.Persist(answered),
                OrganicAiEffect.Release(answered, listOf(ZYGOTE, C1), ReleaseMode.Retire),
            ),
            outputs = listOf(OrganicAiOutput.Finished(ORGANISM, answered.status)),
        )
        assertFalse(answered.isStopped)
        val unconfirmed = answered.copy(sessions = SessionRelease.Unconfirmed)
        spec.assertTransition(
            living(answered),
            OrganicAiIntent.Internal.SessionsReleased(ORGANISM, false),
            living(unconfirmed),
        )
        for (pending in listOf(answered, unconfirmed)) {
            spec.assertIgnored(living(pending), OrganicAiIntent.Public.Resume(ORGANISM))
            spec.assertIgnored(living(pending), OrganicAiIntent.Public.FollowUp(ORGANISM, "more"))
        }
        spec.assertTransition(
            living(unconfirmed),
            OrganicAiIntent.Public.Abort(ORGANISM),
            living(answered),
            effects = listOf(OrganicAiEffect.Release(answered, listOf(ZYGOTE, C1), ReleaseMode.Retire)),
        )
        val released = answered.copy(sessions = SessionRelease.Released)
        spec.assertTransition(
            living(answered),
            OrganicAiIntent.Internal.SessionsReleased(ORGANISM, true),
            living(released),
        )
        assertTrue(released.isStopped)
        spec.assertIgnored(living(released), OrganicAiIntent.Public.Abort(ORGANISM))
    }
}
