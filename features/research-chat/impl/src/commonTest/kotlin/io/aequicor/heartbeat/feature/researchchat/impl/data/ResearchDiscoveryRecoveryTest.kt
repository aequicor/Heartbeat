package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchQuestion
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResearchDiscoveryRecoveryTest {
    private val question = ResearchQuestion("question")
    private val target = EngineTarget(EngineId("koog"), EngineBindingId("binding"), ModelId("model"))
    private val failedCall = SessionItem.ToolCall(
        ItemInfo(ItemId("fetch"), 0, 0),
        ToolCallId("fetch"),
        "web_fetch",
        """{"url":"https://example.com/source"}""",
        ToolCallStatus.Failed,
    )
    private val original = failedDiscovery().copy(id = "original")

    @Test
    fun `replaying failed discovery preserves opened tab and human recovery attempt`() {
        val waiting = original.copy(
            loadState = ResearchLoadState.NeedsUserAction,
            browserTabId = "opened-tab",
            recoveryAttempt = "open-attempt",
            loadError = null,
        )
        val saved = session(waiting).attachDiscovered(question.id, failedDiscovery())
        assertEquals(listOf(waiting), saved.resources)
        assertEquals(setOf(original.id), saved.sharedResourceIds)
    }

    @Test
    fun `history replay during capture preserves full CAS and allows its completion`() {
        val loading = original.copy(
            loadState = ResearchLoadState.Loading,
            browserTabId = "opened-tab",
            recoveryAttempt = "capture-attempt",
            loadError = null,
        )
        val saved = session(loading).attachDiscovered(question.id, failedDiscovery())
        val ready = loading.copy(
            loadState = ResearchLoadState.Ready,
            text = "Recovered evidence",
            recoveryAttempt = null,
        )
        assertEquals(listOf(loading), saved.resources)
        assertEquals(ready, saved.resources.single().applyRecoveryUpdate(loading, ready))
        assertEquals(listOf(ready), session(ready).attachDiscovered(question.id, failedDiscovery()).resources)
    }

    @Test
    fun `explicit successful source update still replaces pending recovery with new evidence`() {
        val waiting = original.copy(
            loadState = ResearchLoadState.NeedsUserAction,
            browserTabId = "opened-tab",
            recoveryAttempt = "open-attempt",
        )
        val refreshed = original.copy(id = "fresh", loadState = ResearchLoadState.Ready, text = "Fresh evidence")
        val saved = session(waiting).attach(question.id, refreshed, ResearchResourceScope.Question)
        assertEquals(listOf(refreshed.copy(id = original.id)), saved.resources)
        assertNull(saved.resources.single().recoveryAttempt)
        assertNull(saved.resources.single().browserTabId)
    }

    private fun failedDiscovery(): ResearchResource = discoveredResources(listOf(failedCall), recoverFailures = true)
        .single()

    private fun session(resource: ResearchResource): ResearchSession =
        ResearchSession("session", "", target, listOf(question), resources = listOf(resource))
}
