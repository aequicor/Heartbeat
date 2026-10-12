package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.attachments.api.AttachmentId
import io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResearchSourceHydrationRaceTest {
    @Test
    fun `late failed fetch preserves recovered PDF and starts question with that attachment`() = runTest {
        val fetching = CompletableDeferred<Unit>()
        val finishFetch = CompletableDeferred<Unit>()
        val fixture = ResearchExecutionFixture(
            backgroundScope,
            fetchResource = {
                fetching.complete(Unit)
                finishFetch.await()
                error("Original transport is still blocked")
            },
        )
        val target = EngineTarget(KoogEngineId, EngineBindingId("connection"), ModelId("model"))
        val session = fixture.repository.createSession(target)
        val source = ResearchResource(
            "source",
            "Paper",
            ResearchResourceKind.Website,
            "https://source.test/paper",
            "text/html",
            loadState = ResearchLoadState.Failed,
        )
        fixture.storage.update(session.id) {
            it.copy(resources = listOf(source), sharedResourceIds = setOf(source.id))
        }
        val run = fixture.repository.run(session.id, session.questions.single().id, "Read this paper")
        fetching.await()
        val recovered = source.copy(
            kind = ResearchResourceKind.Document,
            value = "attachment-pdf",
            sourceUrl = source.value,
            attachmentId = AttachmentId("registered-pdf"),
            mediaType = "application/pdf",
            loadState = ResearchLoadState.Ready,
        )
        fixture.storage.update(session.id) { it.copy(resources = listOf(recovered)) }
        finishFetch.complete(Unit)
        assertTrue(run.accepted.await())
        val stored = fixture.storage.read().single()
        assertEquals(recovered, stored.resources.single())
        assertNull(stored.questions.single().pendingSourcePrompt)
        val native = fixture.native.single()
        val request = assertNotNull(native.request)
        assertTrue(request.parts.filterIsInstance<ContentPart.Resource>().any { it.resource.id == recovered.value })
        native.finish(TurnOutcome.Completed)
        assertTrue(run.completion.await())
    }
}
