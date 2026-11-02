package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.organicai.api.CaseId
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.impl.organism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganicSessionIdentityTest {
    private val owner = LogicalSessionContext(LogicalSessionId("root"), SessionKind.Session)
        .subsession(LogicalSessionId("owner"))

    @Test
    fun `executor authority requires the exact live incarnation and full owner`() {
        val first = organism()
        val binding = first.cellExecution(CellId.ZYGOTE)!!
        val address = OrganicExecutorAddress.parse(binding.instance.id)
        assertTrue(address.permits(first, binding))
        assertFalse(address.permits(first.copy(incarnation = "replacement"), binding))
        assertFalse(address.permits(first.copy(conversation = owner), binding))
        val ended = first.copy(
            cells = first.cells.map {
                it.copy(phase = io.aequicor.heartbeat.feature.organicai.api.CellPhase.Completed("done"))
            },
        )
        assertFalse(address.permits(ended, binding))
        assertNull(first.copy(conversation = owner).cellExecution(CellId.ZYGOTE))
        assertFailsWith<IllegalStateException> { first.copy(incarnation = null).cellExecution(CellId.ZYGOTE) }
    }

    @Test
    fun `zygote retains full conversation and descendants and judges have distinct owned identities`() {
        val organism = organism().copy(conversation = owner)
        assertEquals(owner, organism.cellIdentity(CellId.ZYGOTE))
        val first = organism.cellIdentity(CellId("c1"))!!
        val second = organism.cellIdentity(CellId("c2"))!!
        val judge = organism.judgeIdentity(CaseId("case1"))!!
        listOf(first, second, judge).forEach {
            assertEquals(SessionKind.Subagent, it.kind)
            assertEquals(owner.id, it.owner)
            assertEquals(owner.id, it.creator)
            assertEquals(owner.root, it.root)
        }
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.id, judge.id)
        assertEquals(first, organism.copy(version = 99).cellIdentity(CellId("c1")))
    }

    @Test
    fun `legacy cells and judges have no invented owner`() {
        assertNull(organism().cellIdentity(CellId.ZYGOTE))
        assertNull(organism().cellIdentity(CellId("c1")))
        assertNull(organism().judgeIdentity(CaseId("case1")))
    }
}
