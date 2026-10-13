package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostDirective
import io.aequicor.heartbeat.feature.aiengine.facade.api.withHostDirectives
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioMachineKey
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEntry
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatNotice
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatPart
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatRejection
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSend
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatToolStatus
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProject
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioReplyPart
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioWorkspace
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoice
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun `the list hides helpers, keeps archived chats, puts pinned first and carries settings and permissions`() =
        runTest {
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
            val applied = StudioSessionSettings("pi/default", "high", ApprovalMode.AutoEdits)
            fixture.runtime.state.value = StudioRuntimeState(
                running = setOf("recent"),
                permissions = listOf(permission("recent"), permission("helper")),
                configurations = mapOf("recent" to StudioSessionConfiguration(applied, pendingOperation = "op")),
            )

            val list = fixture.chats.observeList().first()

            assertEquals(listOf("pinned", "recent", "archived"), list.chats.map { it.id })
            assertEquals("p1", list.chats.first().projectId)
            val recent = list.chats[1]
            assertTrue(recent.isRunning)
            assertEquals(StudioChatSettings("pi/default", "high", ApprovalMode.AutoEdits), recent.settings)
            assertTrue(recent.isConfiguring)
            assertTrue(list.chats.last().isArchived)
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
    fun `a new chat needs the start page's model and is not created without one`() = runTest {
        val fixture = Fixture(this)
        fixture.runtime.defaults = DEFAULTS.copy(modelId = "")

        val result = fixture.chats.send(StudioChatSend(null, null, "Hi"))

        assertEquals(rejected(StudioChatRejection.NoModel), result)
        assertTrue(fixture.repository.workspace.value.sessions.isEmpty())
    }

    @Test
    fun `a new chat runs on the chosen model and approvals when the model fits`() = runTest {
        val fixture = Fixture(this)
        fixture.repository.workspace.value = StudioWorkspace(
            listOf(StudioProject("p1", "Heartbeat", StudioEnvironment.Local, "main")),
            emptyList(),
        )
        val chosen = StudioChatSettings(ENGINE_MODEL, approval = ApprovalMode.AutoApprove)

        val accepted = fixture.chats.send(StudioChatSend(null, null, "Hi", settings = chosen))
        val unknown = fixture.chats.send(StudioChatSend(null, null, "Hi", settings = chosen.copy(modelId = "gone")))
        val noProjects = fixture.chats.send(StudioChatSend(null, "p1", "Hi", settings = chosen))

        assertTrue(accepted is StudioChatSendResult.Accepted)
        assertEquals(
            listOf(DEFAULTS.copy(modelId = ENGINE_MODEL, approval = ApprovalMode.AutoApprove)),
            fixture.runtime.settings,
        )
        assertEquals(rejected(StudioChatRejection.NoModel), unknown)
        assertEquals(rejected(StudioChatRejection.NoModel), noProjects)
        assertEquals(1, fixture.repository.workspace.value.sessions.size)
        fixture.runtime.release.complete(Unit)
    }

    @Test
    fun `the composer offers the models with the start page's settings and the profile's efforts`() = runTest {
        val fixture = Fixture(this)
        fixture.efforts.value = EffortConfigurationState.Ready(listOf(EffortChoice(ENGINE_TARGET, "high")))

        val composer = fixture.chats.observeComposer().first()

        assertEquals(StudioChatSettings("pi/default", approval = ApprovalMode.Ask), composer.newChat)
        val engine = composer.models.single { it.id == ENGINE_MODEL }
        assertEquals(listOf("low", "high"), engine.efforts)
        assertEquals("high", engine.newChatEffort)
        assertEquals("pi/b1", engine.connection)
        assertTrue(engine.isTrustSupported && !engine.isProjectSupported)
    }

    @Test
    fun `chat settings, new-chat effort and edits take the studio's paths`() = runTest {
        val fixture = Fixture(this)
        fixture.repository.workspace.value = StudioWorkspace(
            emptyList(),
            listOf(session("a", later), session("helper", later, parentChatId = "a")),
        )
        val model = StudioSettingChange.Model(ENGINE_MODEL)

        assertTrue(fixture.chats.configure("a", model))
        assertFalse(fixture.chats.configure("a", StudioSettingChange.Model("gone")))
        assertFalse(fixture.chats.configure("helper", model))
        assertTrue(fixture.chats.chooseNewChatEffort(ENGINE_MODEL, "high"))
        assertFalse(fixture.chats.chooseNewChatEffort(ENGINE_MODEL, "max"))
        assertTrue(fixture.chats.edit("a", SessionEdit.Rename("  Renamed  ")))
        fixture.registry.studioResult = SendResult.NotRunning
        assertTrue(fixture.chats.edit("a", SessionEdit.SetPinned(true)))
        assertFalse(fixture.chats.edit("a", SessionEdit.Rename(" ")))

        assertEquals(listOf<StudioSettingChange>(model), fixture.runtime.configured)
        assertEquals(
            listOf<MachineIntent>(
                EffortConfigurationIntent.Public.Select(ENGINE_TARGET, "high"),
                AiStudioIntent.Public.Edit("a", SessionEdit.Rename("Renamed")),
                AiStudioIntent.Public.Edit("a", SessionEdit.SetPinned(true)),
            ),
            fixture.registry.intents,
        )
        assertEquals(listOf<SessionEdit>(SessionEdit.SetPinned(true)), fixture.repository.edits)
    }

    @Test
    fun `a chat started outside the screen takes the start page's model until it has its own`() = runTest {
        val stores = ChecklistTestStores()
        val runtime = ChatsRuntime()
        stores.keyValue(ChatSpec).set(
            ChatsKey,
            listOf(
                StudioChatRecord("fresh", "Fresh", later),
                StudioChatRecord(
                    "configured",
                    "Configured",
                    later,
                    configuration = StudioSessionSettings("codex/gpt", approval = ApprovalMode.AutoApprove),
                ),
            ),
        )
        val settings = StoredStudioChatRunSettings(stores, runtime)

        assertEquals(DEFAULTS, settings.settings("fresh"))
        assertEquals(
            DEFAULTS.copy(modelId = "codex/gpt", approval = ApprovalMode.AutoApprove),
            settings.settings("configured"),
        )
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
        val efforts = MutableStateFlow<EffortConfigurationState>(EffortConfigurationState.Loading)
        val registry = ChatsRegistry()
        val chats = ProfileStudioChats(
            ChatsToggles(isEnabled),
            lazyOf(repository),
            lazyOf(runtime),
            lazyOf(StudioChatRunSettings { DEFAULTS }),
            lazyOf(
                StudioChatControls(
                    lazyOf(repository),
                    lazyOf(runtime),
                    object : EffortChoicesView {
                        override val state = efforts
                    },
                    registry,
                ),
            ),
            RunProfile(scope.backgroundScope),
        )
    }

    private class ChatsRegistry : MachineRegistry {
        val intents = mutableListOf<MachineIntent>()
        var studioResult: SendResult = SendResult.Accepted

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? = null

        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(null)

        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult {
            intents += intent
            return if (key == AiStudioMachineKey) studioResult else SendResult.Accepted
        }
    }

    private data class Run(val chat: String, val prompt: String, val attachments: List<ResourceRef>)

    private class ChatsRepository : StudioRepository {
        val workspace = MutableStateFlow(StudioWorkspace(emptyList(), emptyList()))
        val messages = mutableMapOf<String, List<StudioMessage>>()
        val edits = mutableListOf<SessionEdit>()

        override fun observeModels(): Flow<List<StudioModel>> = flowOf(
            listOf(
                StudioModel("pi/default", "Pi", isLocalProjectSupported = true),
                StudioModel(
                    ENGINE_MODEL,
                    "GPT",
                    reasoningEfforts = listOf("low", "high"),
                    isTrustSupported = true,
                    isModelSwitchSupported = true,
                ),
            ),
        )
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

        override suspend fun edit(sessionId: String, edit: SessionEdit) {
            edits += edit
        }

        override suspend fun setBranch(sessionId: String, branch: String) = Unit

        override fun newMessageId(): String = "message"
    }

    private class ChatsRuntime : StudioRuntime {
        override val state = MutableStateFlow(StudioRuntimeState())
        val runs = mutableListOf<Run>()
        val settings = mutableListOf<RunSettings>()
        val configured = mutableListOf<StudioSettingChange>()
        val cancelled = mutableListOf<String>()
        val answers = mutableListOf<Triple<String, String, String>>()
        val release = CompletableDeferred<Unit>()
        var isAccepting = true

        var defaults = DEFAULTS

        override suspend fun defaults(): RunSettings = defaults

        override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome = error("Unused")

        override suspend fun run(
            sessionId: String,
            prompt: String,
            settings: RunSettings,
            attachments: List<ResourceRef>,
            submissionId: String,
            onAccepted: suspend () -> Unit,
        ): RunOutcome {
            runs += Run(sessionId, prompt, attachments)
            this.settings += settings
            if (!isAccepting) return RunOutcome.Failed
            onAccepted()
            release.await()
            return RunOutcome.Completed
        }

        override suspend fun configure(sessionId: String, change: StudioSettingChange) {
            configured += change
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
        val ENGINE_TARGET = EngineTarget(EngineId("pi"), EngineBindingId("b1"), ModelId("gpt"))
        val ENGINE_MODEL = ENGINE_TARGET.studioModelId()
    }
}
