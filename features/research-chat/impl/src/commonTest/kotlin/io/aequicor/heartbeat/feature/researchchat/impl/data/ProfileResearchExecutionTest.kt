package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileResearchExecutionTest {
    private val target = EngineTarget(KoogEngineId, EngineBindingId("connection"), ModelId("model"))

    @Test
    fun `accepted generation outlives its waiter and persists first question discoveries as shared`() = runTest {
        val fixture = ResearchExecutionFixture(backgroundScope)
        val repository = fixture.repository
        val session = repository.createSession(target)
        val question = session.questions.single()
        val run = repository.run(session.id, question.id, "First question")
        assertTrue(run.accepted.await())
        assertTrue(question.id in repository.observe().first().running)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { run.completion.await() }
        waiter.cancelAndJoin()
        val native = fixture.native.single()
        native.complete("https://shared.example", "Shared source evidence")
        assertTrue(run.completion.await())

        val saved = fixture.storage.read().single()
        val persisted = saved.questions.single()
        assertNull(persisted.pendingSegmentStart)
        assertFalse(persisted.hasFailed)
        assertEquals(setOf(saved.resources.single().id), saved.sharedResourceIds)
        assertEquals("Shared source evidence", saved.resources.single().text)
        val messages = persisted.items.filterIsInstance<SessionItem.Message>()
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant), messages.map { it.role })
        assertEquals(listOf(ContentPart.Text("First question")), messages.first().parts)
        assertEquals(listOf(ContentPart.Text("Answer")), messages.last().parts)
        assertTrue(native.isClosed)
        assertTrue(repository.observe().first().running.isEmpty())
    }

    @Test
    fun `later question discoveries stay local and its followup sends only selected source bodies`() = runTest {
        val fixture = ResearchExecutionFixture(backgroundScope)
        val repository = fixture.repository
        val session = repository.createSession(target)
        val first = repository.run(session.id, session.questions.single().id, "First")
        assertTrue(first.accepted.await())
        fixture.native.last().complete("https://shared.example", "SHARED_BODY")
        assertTrue(first.completion.await())

        val secondId = repository.createQuestion(session.id)
        val second = repository.run(session.id, secondId, "Second")
        assertTrue(second.accepted.await())
        fixture.native.last().complete("https://local.example", "LOCAL_BODY")
        assertTrue(second.completion.await())
        val saved = fixture.storage.read().single()
        val sharedId = saved.resources.first { it.value == "https://shared.example" }.id
        val localId = saved.resources.first { it.value == "https://local.example" }.id
        assertEquals(setOf(sharedId), saved.sharedResourceIds)
        assertEquals(setOf(localId), saved.questions.last().resourceIds)

        repository.setResourceSelected(session.id, secondId, sharedId, false)
        val followup = repository.run(session.id, secondId, "Continue second")
        assertTrue(followup.accepted.await())
        val native = fixture.native.last()
        val request = assertNotNull(native.request)
        val context = request.parts.filterIsInstance<ContentPart.Resource>().joinToString("\n") {
            Base64.decode(it.resource.id.substringAfter(',')).decodeToString()
        }
        assertFalse(context.contains("SHARED_BODY"))
        assertFalse(context.contains("https://shared.example"))
        assertTrue(context.contains("LOCAL_BODY"))
        assertEquals(3, fixture.native.size)
        native.complete("https://local.example", "LOCAL_BODY")
        assertTrue(followup.completion.await())
    }
}
