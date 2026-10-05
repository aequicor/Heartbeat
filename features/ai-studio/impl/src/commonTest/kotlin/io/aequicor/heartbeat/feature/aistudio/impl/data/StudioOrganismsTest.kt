package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.OrganismRequest
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.organicai.api.Conception
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioOrganismsTest {
    private val target = EngineTarget(EngineId("pi"), EngineBindingId("binding"), ModelId("astra"))
    private val selections = object : ModelSelections {
        override fun observe(): Flow<ModelSelection> = flowOf(ModelSelection().withDefault(target))
        override suspend fun update(change: (ModelSelection) -> ModelSelection) = change(ModelSelection())
    }
    private val settings = RunSettings(target.studioModelId(), ReasoningEffort.High, ApprovalMode.AutoEdits)
    private val chat = StudioChatRecord(
        "chat-1",
        "Build",
        Instant.fromEpochMilliseconds(0),
        projectId = "/work/project",
        organismId = "chat-1",
    )

    @Test
    fun `the first prompt of an organism chat conceives its organism`() = runTest {
        val machine = OrganicMachine(OrganicAiState.Living())
        var accepted = 0
        val organisms = StudioOrganisms(Registry(machine), selections, profile(this))
        val outcome = organisms.conceive(chat, "Build it", settings, emptyList()) { accepted++ }
        assertEquals(RunOutcome.Completed, outcome)
        assertEquals(1, accepted)
        val expected = Conception(
            OrganismId("chat-1"),
            "Build it",
            target = target,
            workspace = WorkspaceRef("/work/project"),
            trust = TrustLevel.AutoEdits,
        )
        assertEquals(listOf<OrganicAiIntent.Public>(OrganicAiIntent.Public.Conceive(expected)), machine.sent)
    }

    @Test
    fun `an ordinary chat is not an organism and an organism takes no attachments`() = runTest {
        val machine = OrganicMachine(OrganicAiState.Living())
        val organisms = StudioOrganisms(Registry(machine), selections, profile(this))
        assertNull(organisms.conceive(chat.copy(organismId = null), "Hi", settings, emptyList()) {})
        val files = listOf(ResourceRef("attachment:image", "image/png"))
        assertFailsWith<IllegalArgumentException> { organisms.conceive(chat, "Fix the image", settings, files) {} }
        assertEquals(emptyList(), machine.sent)
    }

    @Test
    fun `an organism chat is admitted only when its organism could be conceived`() = runTest {
        val organisms = { machine: OrganicMachine? -> StudioOrganisms(Registry(machine), selections, profile(this)) }
        val living = organisms(OrganicMachine(OrganicAiState.Living()))
        val request = OrganismRequest("Build", settings)
        assertEquals("chat-2", living.admit("chat-2", request, isWorktree = false))
        val long = request.copy(goal = "x".repeat(OrganismBounds.MAX_GOAL + 1))
        assertFailsWith<IllegalArgumentException> { living.admit("chat-2", long, isWorktree = false) }
        assertFailsWith<IllegalArgumentException> { living.admit("chat-2", request, isWorktree = true) }
        assertFailsWith<IllegalStateException> { organisms(null).admit("chat-2", request, isWorktree = false) }
        val broken = organisms(OrganicMachine(OrganicAiState.Broken))
        assertFailsWith<IllegalStateException> { broken.admit("chat-2", request, isWorktree = false) }
    }

    @Test
    fun `conception fails while organic AI is off, broken or refuses`() = runTest {
        val organisms = { machine: OrganicMachine? -> StudioOrganisms(Registry(machine), selections, profile(this)) }
        assertFailsWith<IllegalStateException> { organisms(null).conceive(chat, "Build", settings, emptyList()) {} }
        assertFailsWith<IllegalStateException> {
            organisms(OrganicMachine(OrganicAiState.Broken)).conceive(chat, "Build", settings, emptyList()) {}
        }
        val refusing = OrganicMachine(OrganicAiState.Living()).apply { result = SendResult.Ignored }
        assertFailsWith<IllegalStateException> { organisms(refusing).conceive(chat, "Build", settings, emptyList()) {} }
    }

    @Test
    fun `a viewed session shows its history`() = runTest {
        val ref = SessionRef(EngineId("pi"), SessionSourceId("local"), "cell")
        val history = listOf(
            SessionItem.Message(ItemInfo(ItemId("p"), 0, 0), MessageRole.User, listOf(ContentPart.Text("Task"))),
            SessionItem.Message(ItemInfo(ItemId("r"), 1, 0), MessageRole.Assistant, listOf(ContentPart.Text("Done"))),
        )
        val facade = ViewedFacade(ref, history)
        val messages = StudioSessionViewer(facade).observe(ref, reopening).first { it.size == 2 }
        assertEquals("Task", (messages[0] as StudioMessage.Prompt).text)
        assertEquals("Done", (messages[1] as StudioMessage.Reply).text)
        assertEquals(emptyList<ResumeSessionRequest>(), facade.resumptions)
    }

    @Test
    fun `a session whose engine keeps no stored history is viewed through a reopened handle`() = runTest {
        val ref = SessionRef(EngineId("codex"), SessionSourceId("codex"), "cell")
        val history = listOf(
            SessionItem.Message(ItemInfo(ItemId("p"), 0, 0), MessageRole.User, listOf(ContentPart.Text("Task"))),
        )
        val facade = ViewedFacade(ref, history, hasStoredHistory = false)
        val messages = StudioSessionViewer(facade).observe(ref, reopening).first { it.size == 1 }
        assertEquals("Task", (messages.single() as StudioMessage.Prompt).text)
        assertEquals(listOf(reopening), facade.resumptions)
        assertEquals(1, facade.closes)
    }

    @Test
    fun `an unreadable viewed session stays empty instead of failing the screen`() = runTest {
        val ref = SessionRef(EngineId("pi"), SessionSourceId("local"), "gone")
        val messages = StudioSessionViewer(ViewedFacade(ref = null, emptyList())).observe(ref, reopening).first()
        assertTrue(messages.isEmpty())
    }

    private val reopening = ResumeSessionRequest(
        EngineTarget(EngineId("pi"), EngineBindingId("binding"), ModelId("model")),
        areDetachedToolsEnabled = true,
    )
}

private class OrganicMachine(initial: OrganicAiState) :
    MachineRef<OrganicAiState, OrganicAiIntent.Public, OrganicAiOutput> {
    override val name: String = OrganicAiMachineKey.name
    override val state = MutableStateFlow(initial)
    override val outputs: Flow<OrganicAiOutput> = emptyFlow()
    val sent = mutableListOf<OrganicAiIntent.Public>()
    var result = SendResult.Accepted

    override suspend fun send(intent: OrganicAiIntent.Public): SendResult {
        sent += intent
        return result
    }
}

private class Registry(private val machine: OrganicMachine?) : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // The fake resolves only the organic AI key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = if (key == OrganicAiMachineKey) machine as MachineRef<S, P, O>? else null

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = SendResult.NotRunning
}

/**
 * A facade storing one session [ref] with [items] in its history; any other session cannot be read. Without
 * [hasStoredHistory] the history is served only by a reopened handle, as Codex serves it.
 */
private class ViewedFacade(
    private val ref: SessionRef?,
    private val items: List<SessionItem>,
    private val hasStoredHistory: Boolean = true,
) : EngineFacade {
    val resumptions = mutableListOf<ResumeSessionRequest>()
    var closes = 0

    override val engines: EngineCatalog get() = error("unused")
    override val bindings: EngineBindings get() = error("unused")
    override val models: ModelCatalog get() = error("unused")
    override val providerUsage: ProviderUsageCatalog get() = error("unused")
    override val sessions: SessionCatalog = object : SessionCatalog {
        override suspend fun page(query: SessionQuery, request: PageRequest) = error("unused")

        override suspend fun refresh(query: SessionQuery) = error("unused")

        override suspend fun get(ref: SessionRef): EngineSession {
            check(ref == this@ViewedFacade.ref) { "Unknown session" }
            return object : EngineSession {
                override val summary: StateFlow<SessionSummary> = MutableStateFlow(SessionSummary(ref))
                override val features: EngineFeatures = object : EngineFeatures {
                    @Suppress("UNCHECKED_CAST") // The fake offers only its history or its resumption.
                    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> = when {
                        key == SessionHistory && hasStoredHistory -> FeatureAccess.Available(history as F)
                        key == ResumesSessions && !hasStoredHistory -> FeatureAccess.Available(resumes as F)
                        else -> FeatureAccess.Unsupported
                    }
                }
            }
        }
    }

    private val resumes = object : ResumesSessions {
        override suspend fun resume(request: ResumeSessionRequest): ActiveSession {
            resumptions += request
            return opened(checkNotNull(ref))
        }
    }

    private fun opened(ref: SessionRef) = object : ActiveSession {
        override val ref: SessionRef = ref
        override val route: ExecutionRoute get() = error("unused")
        override val state: StateFlow<ActiveSessionState> = MutableStateFlow(ActiveSessionState.Ready())
        override val features: EngineFeatures = object : EngineFeatures {
            @Suppress("UNCHECKED_CAST") // The open handle offers only its history.
            override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
                if (key == SessionHistory) FeatureAccess.Available(history as F) else FeatureAccess.Unsupported
        }

        override suspend fun close() {
            closes++
        }
    }

    private val history = object : SessionHistory {
        override suspend fun page(request: HistoryPageRequest): HistoryPage =
            HistoryPage(items, null, null, HistoryCheckpoint("checkpoint"), HistoryCoverage.Complete)

        override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow { awaitCancellation() }
    }
}

/** A profile whose coroutines run in the test's background. */
private fun profile(test: TestScope) = object : ScopeHandle {
    override val name = "organisms-test-profile"
    override val coroutineScope: CoroutineScope = test.backgroundScope
    override val isClosed = false
    override val savedState: ScopeSavedState get() = error("Unused")
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
}
