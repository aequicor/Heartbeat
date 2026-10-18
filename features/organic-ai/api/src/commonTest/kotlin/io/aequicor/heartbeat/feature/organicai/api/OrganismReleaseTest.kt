package io.aequicor.heartbeat.feature.organicai.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganismReleaseTest {
    private val spec = OrganicAiMachineSpec

    private fun living(vararg organisms: Organism) = OrganicAiState.Living(organisms.associateBy { it.id })

    private val killed = CellPhase.Dead(DeathCause.Aborted)

    /** An aborted organism whose zygote and one cell were killed, in the given release state. */
    private fun aborted(sessions: SessionRelease, version: Long = 2): Organism = organism(
        cell(C1, phase = killed),
        cell(C2, phase = CellPhase.Completed("ok")),
        zygote = zygoteCell(killed),
        status = OrganismStatus.Aborted,
        version = version,
    ).copy(sessions = sessions)

    @Test
    fun `an abort only marks the organism and waits for the host to release its sessions`() {
        val marked = aborted(SessionRelease.Releasing)
        spec.assertTransition(
            living(organism(cell(C1), cell(C2, phase = CellPhase.Completed("ok")))),
            OrganicAiIntent.Public.Abort(ORGANISM),
            living(marked),
            effects = listOf(
                OrganicAiEffect.Persist(marked),
                OrganicAiEffect.Release(marked, listOf(ZYGOTE, C1), ReleaseMode.Lyse),
            ),
            outputs = listOf(OrganicAiOutput.Finished(ORGANISM, OrganismStatus.Aborted)),
        )
        assertFalse(marked.isStopped)
        assertFalse(marked.copy(sessions = SessionRelease.Unconfirmed).isStopped)
    }

    @Test
    fun `an abort with no living cell has nothing to wait for`() {
        val ended = organism(zygote = zygoteCell(CellPhase.Completed("done")))
        val marked = ended.copy(
            cells = ended.cells.map { it.copy(phase = CellPhase.Completed("done")) },
            status = OrganismStatus.Aborted,
            version = 2,
        )
        spec.assertTransition(
            living(ended),
            OrganicAiIntent.Public.Abort(ORGANISM),
            living(marked),
            effects = listOf(OrganicAiEffect.Persist(marked)),
            outputs = listOf(OrganicAiOutput.Finished(ORGANISM, OrganismStatus.Aborted)),
        )
        assertTrue(marked.isStopped)
    }

    @Test
    fun `a confirmed release makes the aborted organism stopped without saving it again`() {
        val releasing = aborted(SessionRelease.Releasing)
        val released = releasing.copy(sessions = SessionRelease.Released)
        spec.assertTransition(
            living(releasing),
            OrganicAiIntent.Internal.SessionsReleased(ORGANISM, isConfirmed = true),
            living(released),
        )
        assertTrue(released.isStopped)
        assertEquals(releasing.version, released.version)
    }

    @Test
    fun `an unconfirmed release keeps the organism unstopped until another abort retries`() {
        val releasing = aborted(SessionRelease.Releasing)
        val unconfirmed = releasing.copy(sessions = SessionRelease.Unconfirmed)
        spec.assertTransition(
            living(releasing),
            OrganicAiIntent.Internal.SessionsReleased(ORGANISM, isConfirmed = false),
            living(unconfirmed),
        )
        assertFalse(unconfirmed.isStopped)

        val retried = unconfirmed.copy(sessions = SessionRelease.Releasing)
        spec.assertTransition(
            living(unconfirmed),
            OrganicAiIntent.Public.Abort(ORGANISM),
            living(retried),
            effects = listOf(OrganicAiEffect.Release(retried, listOf(ZYGOTE, C1), ReleaseMode.Lyse)),
        )
        spec.assertTransition(
            living(retried),
            OrganicAiIntent.Internal.SessionsReleased(ORGANISM, isConfirmed = true),
            living(retried.copy(sessions = SessionRelease.Released)),
        )
    }

    @Test
    fun `an abort while the release runs or after it finished changes nothing`() {
        spec.assertIgnored(living(aborted(SessionRelease.Releasing)), OrganicAiIntent.Public.Abort(ORGANISM))
        spec.assertIgnored(living(aborted(SessionRelease.Released)), OrganicAiIntent.Public.Abort(ORGANISM))
    }

    @Test
    fun `a release report counts only for the abort that asked for it`() {
        val report = OrganicAiIntent.Internal.SessionsReleased(ORGANISM, isConfirmed = true)
        spec.assertIgnored(living(aborted(SessionRelease.Released)), report)
        spec.assertIgnored(living(aborted(SessionRelease.Unconfirmed)), report)
        spec.assertIgnored(living(organism()), report)
        spec.assertIgnored(living(organism(status = OrganismStatus.Completed("done"))), report)
        spec.assertIgnored(living(), report)
    }

    @Test
    fun `a failed release of an abort reports unconfirmed while other failed releases stay logged only`() {
        val releasing = aborted(SessionRelease.Releasing)
        assertEquals(
            OrganicAiIntent.Internal.SessionsReleased(ORGANISM, isConfirmed = false),
            spec.onEffectFailure(OrganicAiEffect.Release(releasing, listOf(ZYGOTE), ReleaseMode.Lyse), Exception()),
        )
        val ordinary = organism()
        assertNull(
            spec.onEffectFailure(OrganicAiEffect.Release(ordinary, listOf(ZYGOTE), ReleaseMode.Retire), Exception()),
        )
    }

    @Test
    fun `a restored aborted organism is released because this process holds none of its sessions`() {
        // The release state is not journaled, so a restored organism has the default.
        val restored = aborted(SessionRelease.Released)
        spec.assertTransition(
            OrganicAiState.Awakening,
            OrganicAiIntent.Internal.Restored(listOf(restored)),
            living(restored),
        )
        assertTrue(restored.isStopped)
        assertEquals(SessionRelease.Released, organism().sessions)
    }

    @Test
    fun `only a completed or a released aborted organism is stopped`() {
        assertFalse(organism().isStopped)
        assertTrue(organism(status = OrganismStatus.Completed("done")).isStopped)
    }
}
