package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganicIdentityAdaptersTest {
    private val key = CellKey(ORGANISM, C1)
    private val route = CellRoute(TARGET, null, TrustLevel.Ask)

    @Test
    fun `cell identity is carried by both initial creation and restart attachment`() = runTest {
        val identity = LogicalSessionContext(LogicalSessionId("owner"), SessionKind.Session)
            .subagent(LogicalSessionId("cell"))
        val native = FakeSession(session("owned"))
        val fresh = FakeFacade(ArrayDeque(listOf(native)))
        cellSessions(fresh).open(key, route.copy(logicalSession = identity), existing = null)
        assertEquals(identity, fresh.creations.single().logicalSession)
        val restarted = FakeFacade(stored = mapOf(native.ref to FakeSession(native.ref)))
        cellSessions(restarted).open(key, route.copy(logicalSession = identity), existing = native.ref)
        assertEquals(identity, restarted.resumptions.single().logicalSession)
    }
}
