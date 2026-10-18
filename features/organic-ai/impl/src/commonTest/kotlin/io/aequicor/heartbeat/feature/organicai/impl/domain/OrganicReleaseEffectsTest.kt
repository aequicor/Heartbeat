package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.DeathCause
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEffect
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.ReleaseMode
import io.aequicor.heartbeat.feature.organicai.api.SessionRelease
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.ZYGOTE
import io.aequicor.heartbeat.feature.organicai.impl.cell
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.organicai.impl.session
import io.aequicor.heartbeat.feature.organicai.impl.zygoteCell
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class OrganicReleaseEffectsTest : OrganicEffectsFixture() {
    private fun aborted(): Organism {
        val killed = CellPhase.Dead(DeathCause.Aborted)
        return organism(cell(C1, phase = killed), zygote = zygoteCell(killed))
            .copy(status = OrganismStatus.Aborted, sessions = SessionRelease.Releasing)
    }

    @Test
    fun `the release of an abort is confirmed once every cell was seen to stop`() = runTest {
        effects.handle(OrganicAiEffect.Release(aborted(), listOf(ZYGOTE, C1), ReleaseMode.Lyse), machine)
        assertEquals(
            listOf<OrganicAiIntent>(OrganicAiIntent.Internal.SessionsReleased(ORGANISM, isConfirmed = true)),
            machine.sent,
        )
    }

    @Test
    fun `one cell that did not stop leaves the release of an abort unconfirmed`() = runTest {
        cells.unstoppable += C1
        effects.handle(OrganicAiEffect.Release(aborted(), listOf(ZYGOTE, C1), ReleaseMode.Lyse), machine)
        // The cells are released together, so the stuck one does not keep the other from going.
        assertEquals(listOf(ZYGOTE, C1), cells.released.map { it.first.cell })
        assertEquals(
            listOf<OrganicAiIntent>(OrganicAiIntent.Internal.SessionsReleased(ORGANISM, isConfirmed = false)),
            machine.sent,
        )
    }

    @Test
    fun `the release of an ordinary kill or retirement reports nothing`() = runTest {
        val lysed = organism(cell(C1, phase = CellPhase.Dead(DeathCause.Lysed(CaseId("k1"), "cancer"))))
        effects.handle(OrganicAiEffect.Release(lysed, listOf(C1), ReleaseMode.Lyse), machine)
        effects.handle(OrganicAiEffect.Release(lysed, listOf(C1), ReleaseMode.Retire), machine)
        assertEquals(emptyList(), machine.sent)
    }

    @Test
    fun `unconfirmed lysis keeps the cell harness connected until a confirmed retry`() = runTest {
        val ended = aborted().copy(harnesses = setOf("verify"))
        cells.unstoppable += C1
        effects.handle(OrganicAiEffect.Release(ended, listOf(C1), ReleaseMode.Lyse), machine)
        assertEquals(emptyList(), harnesses.disconnected)
        cells.unstoppable.clear()
        effects.handle(OrganicAiEffect.Release(ended, listOf(C1), ReleaseMode.Lyse), machine)
        assertEquals(listOf(session("c1") to setOf("verify")), harnesses.disconnected)
    }

    @Test
    fun `completed organisms retire the zygote and retry lysis of an unconfirmed dead child`() = runTest {
        val ended = organism(
            cell(C1, phase = CellPhase.Dead(DeathCause.Lysed(CaseId("k1"), "killed"))),
            zygote = zygoteCell(CellPhase.Completed("answer")),
        ).copy(status = OrganismStatus.Completed("answer"), sessions = SessionRelease.Releasing)
        val release = OrganicAiEffect.Release(ended, listOf(ZYGOTE, C1), ReleaseMode.Retire)
        cells.unstoppable += C1
        effects.handle(release, machine)
        assertEquals(listOf(ReleaseMode.Retire, ReleaseMode.Lyse), cells.released.map { it.third })
        assertEquals(OrganicAiIntent.Internal.SessionsReleased(ORGANISM, false), machine.sent.last())
        cells.unstoppable.clear()
        effects.handle(release, machine)
        assertEquals(OrganicAiIntent.Internal.SessionsReleased(ORGANISM, true), machine.sent.last())
    }
}
