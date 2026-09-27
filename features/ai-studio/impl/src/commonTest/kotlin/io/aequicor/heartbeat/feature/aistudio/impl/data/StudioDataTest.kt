package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.impl.domain.AgentEvent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.AgentRequest
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModels
import io.aequicor.heartbeat.feature.aistudio.impl.domain.TestClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioDataTest {
    @Test
    fun `seed opens with projects, recent sessions and one archived prototype`() = runTest {
        val repository = InMemoryStudioRepository(TestClock(this))
        val workspace = repository.observeWorkspace().first()
        assertEquals(listOf("heartbeat", "aequicor-site"), workspace.projects.map { it.name })
        assertEquals("p-heartbeat", repository.defaultProjectId())
        assertEquals(1, workspace.sessions.count { it.isArchived })
        assertTrue(workspace.sessions.filterNot { it.isArchived }.all { repository.hasTranscript(it.id) })
        assertEquals("s-build", workspace.sessions.maxBy { it.updatedAt }.id)
    }

    @Test
    fun `prompts move a session to the top while streamed replacements do not`() = runTest {
        val clock = TestClock(this)
        val repository = InMemoryStudioRepository(clock)
        val session = repository.createSession(null, "New")
        repository.append("s-greeting", StudioMessage.Prompt(repository.newMessageId(), clock.now(), "Hi"))
        val reply = StudioMessage.Reply(repository.newMessageId(), clock.now(), isStreaming = true)
        repository.append(session.id, reply)
        repository.replace(session.id, reply.copy(text = "Streamed"))

        val workspace = repository.observeWorkspace().first()
        assertEquals(clock.now(), workspace.session("s-greeting")?.updatedAt)
        assertEquals(
            listOf<StudioMessage>(reply.copy(text = "Streamed")),
            repository.observeMessages(session.id).first(),
        )
    }

    @Test
    fun `archiving unpins and restoring keeps the session unpinned`() = runTest {
        val repository = InMemoryStudioRepository(TestClock(this))
        repository.edit("s-facade", SessionEdit.SetPinned(true))
        repository.edit("s-facade", SessionEdit.SetArchived(true))
        repository.edit("s-facade", SessionEdit.SetArchived(false))
        val session = repository.observeWorkspace().first().session("s-facade")
        assertFalse(session?.isPinned == true)
        assertFalse(session?.isArchived == true)
    }

    @Test
    fun `auto approval lets the demo agent edit files and push a branch`() = runTest {
        val request = AgentRequest(
            prompt = "Спланируй фичу",
            history = emptyList(),
            settings = DefaultRunSettings.copy(approval = ApprovalMode.AutoApprove),
            project = null,
        )
        val events = ScriptedStudioAgent().run(request).toList()
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.diff != null })
        assertEquals("studio/plan", events.filterIsInstance<AgentEvent.BranchCreated>().single().name)
    }

    @Test
    fun `asking for approval keeps the agent read only and low effort skips exploration`() = runTest {
        val request = AgentRequest(
            prompt = "Проведи ревью",
            history = emptyList(),
            settings = DefaultRunSettings.copy(effort = ReasoningEffort.Low, modelId = StudioModels.last().id),
            project = null,
        )
        val events = ScriptedStudioAgent().run(request).toList()
        assertTrue(events.none { it is AgentEvent.ToolStarted || it is AgentEvent.BranchCreated })
        assertTrue(events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.text }.contains("Ревью"))
    }

    private suspend fun InMemoryStudioRepository.hasTranscript(id: String): Boolean =
        observeMessages(id).first().isNotEmpty()
}
