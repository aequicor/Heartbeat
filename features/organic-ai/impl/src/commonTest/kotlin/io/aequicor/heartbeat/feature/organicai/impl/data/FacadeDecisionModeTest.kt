package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.ORGANISM
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellKey
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellRoute
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FacadeDecisionModeTest {
    private val key = CellKey(ORGANISM, C1)
    private val route = CellRoute(TARGET, WorkspaceRef("project"), TrustLevel.AutoEdits)

    @Test
    fun `automatic cell route reaches the native prompt without raising trust`() = runTest {
        val native = FakeSession(session("automatic"))
        val cells = cellSessions(FakeFacade(ArrayDeque(listOf(native))))
        val automatic = route.copy(trust = TrustLevel.Ask, decisionMode = AgentToolDecisionMode.AutomaticSafety)
        cells.open(key, automatic, existing = null).submit(RequestId("first"), "work", TrustLevel.Ask)
        assertEquals(AgentToolDecisionMode.AutomaticSafety, native.prompts.single().decisionMode)
        assertEquals(TrustLevel.Ask, native.prompts.single().trust)
        cells.open(key, automatic, existing = native.ref, generation = 2)
            .submit(RequestId("recovered"), "recover", TrustLevel.Ask)
        assertEquals(AgentToolDecisionMode.AutomaticSafety, native.prompts.last().decisionMode)
        cells.open(key, route, existing = native.ref, generation = 3)
            .submit(RequestId("ordinary"), "ordinary", TrustLevel.AutoEdits)
        assertEquals(AgentToolDecisionMode.Interactive, native.prompts.last().decisionMode)
    }
}
