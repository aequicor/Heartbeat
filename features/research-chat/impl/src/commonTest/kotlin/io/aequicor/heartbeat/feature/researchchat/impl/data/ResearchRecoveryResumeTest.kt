package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.researchchat.api.ResearchQuestion
import io.aequicor.heartbeat.feature.researchchat.api.ResearchRecoveryAction
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResearchRecoveryResumeTest {
    private val target = EngineTarget(KoogEngineId, EngineBindingId("connection"), ModelId("model"))

    @Test
    fun `rejected resume keeps its prompt waiting and other questions still resume`() = runTest {
        val fixture = ResearchExecutionFixture(backgroundScope, onRecover = { _, _, _ -> })
        // An unrecoverable earlier segment makes the first start fail its precondition.
        val blocked = ResearchQuestion("blocked", pendingSegmentStart = 0, pendingSourcePrompt = "First prompt")
        val ready = ResearchQuestion("ready", pendingSourcePrompt = "Second prompt")
        fixture.storage.add(ResearchSession("session", "", target, listOf(blocked, ready)))

        fixture.repository.recoverResource("session", "source", ResearchRecoveryAction.Continue)
        runCurrent()

        val saved = fixture.storage.read().single().questions.associateBy { it.id }
        assertEquals("First prompt", saved.getValue("blocked").pendingSourcePrompt)
        assertNull(saved.getValue("ready").pendingSourcePrompt)
        val request = assertNotNull(fixture.native.single().request)
        assertTrue(ContentPart.Text("Second prompt") in request.parts)
    }

    @Test
    fun `revoked profile recovery settles a live caller without an error`() = runTest {
        val fixture = ResearchExecutionFixture(
            backgroundScope,
            onRecover = { _, _, _ -> throw CancellationException("Recovery revoked") },
        )
        fixture.storage.add(ResearchSession("session", "", target, listOf(ResearchQuestion("question"))))

        fixture.repository.recoverResource("session", "source", ResearchRecoveryAction.Open)

        assertTrue(fixture.native.isEmpty())
    }
}
