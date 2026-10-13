package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostDirective
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEntry
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatNotice
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatPart
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatRejection
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSend
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatToolStatus
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProject
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioReplyPart
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProfileStudioChatsTest {
    private val earlier = Instant.fromEpochSeconds(1_000)
    private val later = Instant.fromEpochSeconds(2_000)

    @Test
    fun `the list hides helpers and archived chats, puts pinned first and carries live permissions`() = runTest {
        val fixture = Fixture(this)
        fixture.repository.workspace.value = StudioWorkspace(
            projects = listOf(StudioProject("p1", "Heartbeat", StudioEnvironment.Local, "main")),
            sessions = listOf(
                session("recent", later),
                session("pinned", earlier, isPinned = true, projectId = "p1"),
                session("helper", later, parentChatId = "recent"),
                session("archived", later, isArchived = true),
            ),
        )
        fixture.runtime.state.value = StudioRuntimeState(
            running = setOf("recent"),
            permissions = listOf(permission("recent"), permission("helper")),
        )

        val list = fixture.chats.observeList().first()

        assertEquals(listOf("pinned", "recent"), list.chats.map { it.id })
        assertEquals("p1", list.chats.first().projectId)
        assertTrue(list.chats.last().isRunning)
        assertEquals(listOf("recent"), list.permissions.map { it.sessionId })
        assertEquals(listOf("Heartbeat"), list.projects.map { it.name })
    }

    @Test
    fun `nothing is listed or sent while profile chats are off`() = runTest {
        val fixture = Fixture(this, isEnabled = false)
        fixture.repository.workspace.value = StudioWorkspace(emptyList(), listOf(session("a", later)))

        assertTrue(fixture.chats.observeList().first().chats.isEmpty())
        assertEquals(
            StudioChatSendResult.Rejected(StudioChatRejection.Unavailable),
            fixture.chats.send(StudioChatSend("a", null, "hi")),
        )
    }

    @Test
    fun `transcripts drop host directives and keep reply parts in order`() = runTest {
        val fixture = Fixture(this)
        val tool = StudioToolRun("t1", "ls", ToolRunStatus.Done, output = "file")
        fixture.repository.messages["a"] = listOf(
            StudioMessage.Prompt("m1", earlier, withHostDirectives("Привет", listOf(hostDirective("secret")))),
            StudioMessage.Reply(
                "m2",
                later,
                text = "ignored",
                parts = listOf(StudioReplyPart.Reasoning("r", "think"), StudioReplyPart.Tool(tool)),
                isStreaming = true,
            ),
            StudioMessage.Reply("m3", later, text = "plain", tools = listOf(tool)),
            StudioMessage.Failed("m4", later),
        )

        val entries = fixture.chats.observeTranscript("a").first()

        assertEquals(StudioChatEntry.Prompt("m1", earlier, "Привет", 0), entries[0])
        val toolPart = StudioChatPart.Tool("t1", "ls", StudioChatToolStatus.Done, "file")
        assertEquals(
            StudioChatEntry.Reply("m2", later, listOf(StudioChatPart.Reasoning("think"), toolPart), isStreaming = true),
            entries[1],
        )
        assertEquals(listOf(StudioChatPart.Text("plain"), toolPart), (entries[2] as StudioChatEntry.Reply).parts)
        assertEquals(StudioChatEntry.Notice("m4", later, StudioChatNotice.Failed), entries[3])
    }

    @Test
    fun `a prompt without a chat creates one and returns once the engine accepted it`() = runTest {
        val fixture = Fixture(this)
        val attachment = ResourceRef("attachment:1", "text/markdown")

        val result = fixture.chats.send(StudioChatSend(null, null, "  Summarize the page  ", listOf(attachment)))

        val created = fixture.repository.workspace.value.sessions.single()
        assertEquals(StudioChatSendResult.Accepted(created.id), result)
        assertEquals("Summarize the page", created.title)
        assertEquals(listOf(Run(created.id, "Summarize the page", listOf(attachment))), fixture.runtime.runs)
        fixture.runtime.release.complete(Unit)
    }

    @Test
    fun `refusals name their reason`() = runTest {
        val fixture = Fixture(this)
        fixture.repository.workspace.value = StudioWorkspace(
            emptyList(),
            listOf(session("busy", later), session("closed", later, isContinuable = false)),
        )
        fixture.runtime.state.value = StudioRuntimeState(running = setOf("busy"))

        assertEquals(rejected(StudioChatRejection.Busy), fixture.chats.send(StudioChatSend("busy", null, "x")))
        assertEquals(
            rejected(StudioChatRejection.NotContinuable),
            fixture.chats.send(StudioChatSend("closed", null, "x")),
        )
        assertEquals(rejected(StudioChatRejection.UnknownChat), fixture.chats.send(StudioChatSend("gone", null, "x")))
        assertEquals(
            rejected(StudioChatRejection.UnknownProject),
            fixture.chats.send(StudioChatSend(null, "missing", "x")),
        )
        assertTrue(fixture.runtime.runs.isEmpty())
    }

    @Test
    fun `a turn the engine never accepted is reported as failed`() = runTest {
        val fixture = Fixture(this)
        fixture.repository.workspace.value = StudioWorkspace(emptyList(), listOf(session("a", later)))
        fixture.runtime.isAccepting = false

        assertEquals(rejected(StudioChatRejection.Failed), fixture.chats.send(StudioChatSend("a", null, "x")))
    }

    @Test
    fun `stop and permission answers reach only live work`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.state.value = StudioRuntimeState(running = setOf("a"), permissions = listOf(permission("a")))
        val answer = StudioPermissionAnswer.Text("ok")

        assertTrue(fixture.chats.stop("a"))
        assertFalse(fixture.chats.stop("b"))
        assertTrue(fixture.chats.respond("a", "r1", "allow", answer))
        assertFalse(fixture.chats.respond("a", "r1", "unknown"))
        assertFalse(fixture.chats.respond("a", "r2", "allow"))

        assertEquals(listOf("a"), fixture.runtime.cancelled)
        assertEquals(listOf(Triple("a", "r1", "allow")), fixture.runtime.answers)
    }

    private fun rejected(reason: StudioChatRejection) = StudioChatSendResult.Rejected(reason)

    private fun permission(chat: String) =
        StudioPermission(chat, "r1", "Run?", listOf(StudioPermissionOption("allow", "Allow")))

    private fun session(
        id: String,
        updatedAt: Instant,
        isPinned: Boolean = false,
        isArchived: Boolean = false,
        isContinuable: Boolean = true,
        projectId: String? = null,
        parentChatId: String? = null,
    ) = StudioSession(
        id = id,
        projectId = projectId,
        title = id,
        updatedAt = updatedAt,
        isPinned = isPinned,
        isArchived = isArchived,
        isContinuable = isContinuable,
        parentChatId = parentChatId,
    )

    private class Fixture(scope: TestScope, isEnabled: Boolean = true) {
        val repository = ChatsRepository()
        val runtime = ChatsRuntime()
        val chats = ProfileStudioChats(
            ChatsToggles(isEnabled),
            lazyOf(repository),
            lazyOf(runtime),
            lazyOf(StudioChatSettings { DEFAULTS }),
            RunProfile(scope.backgroundScope),
        )
    }

    private data class Run(val chat: String, val prompt: String, val attachments: List<ResourceRef>)

    private class ChatsRepository : StudioRepository {
        val workspace = MutableStateFlow(StudioWorkspace(emptyList(), emptyList()))
        val messages = mutableMapOf<String, List<StudioMessage>>()
        private var next = 0

        override fun observeWorkspace(): Flow<StudioWorkspace> = workspace

        override fun observeMessages(sessionId: String): Flow<List<StudioMessage>> =
            flowOf(messages[sessionId].orEmpty())

        override suspend fun defaultProjectId(): String? = null

        override suspend fun createSession(projectId: String?, title: String): StudioSession {
            val created = StudioSession("chat-${++next}", projectId, title, Instant.fromEpochSeconds(3_000))
            workspace.update { it.copy(sessions = it.sessions + created) }
            return created
        }

        override suspend fun append(sessionId: String, message: StudioMessage) = Unit

        override suspend fun replace(sessionId: String, message: StudioMessage) = Unit

        override suspend fun edit(sessionId: String, edit: SessionEdit) = Unit

        override suspend fun setBranch(sessionId: String, branch: String) = Unit

        override fun newMessageId(): String = "message"
    }

    private class ChatsRuntime : StudioRuntime {
        override val state = MutableStateFlow(StudioRuntimeState())
        val runs = mutableListOf<Run>()
        val cancelled = mutableListOf<String>()
        val answers = mutableListOf<Triple<String, String, String>>()
        val release = CompletableDeferred<Unit>()
        var isAccepting = true

        override suspend fun defaults(): RunSettings = DEFAULTS

        override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome = error("Unused")

        override suspend fun run(
            sessionId: String,
            prompt: String,
            settings: RunSettings,
            attachments: List<ResourceRef>,
            submissionId: String,
            environment: StudioChatEnvironment?,
            onAccepted: suspend () -> Unit,
        ): RunOutcome {
            runs += Run(sessionId, prompt, attachments)
            if (!isAccepting) return RunOutcome.Failed
            onAccepted()
            release.await()
            return RunOutcome.Completed
        }

        override suspend fun cancel(sessionId: String) {
            cancelled += sessionId
        }

        override suspend fun respond(
            sessionId: String,
            requestId: String,
            optionId: String,
            answer: StudioPermissionAnswer?,
        ) {
            answers += Triple(sessionId, requestId, optionId)
        }
    }

    private class ChatsToggles(private val isEnabled: Boolean) : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // Only the studio runtime flag is read.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(value(toggle) as T)

        @Suppress("UNCHECKED_CAST") // Only the studio runtime flag is read.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle) as T

        private fun value(toggle: FeatureToggle<*>): Boolean = toggle == StudioEngineRuntime && isEnabled
    }

    private companion object {
        val DEFAULTS = RunSettings("pi/default", ReasoningEffort.Medium, ApprovalMode.Ask)
    }
}
