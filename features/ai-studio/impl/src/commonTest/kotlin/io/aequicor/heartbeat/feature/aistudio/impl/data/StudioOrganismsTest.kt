package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionObservationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.OrganismRequest
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioReplyPart
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.Conception
import io.aequicor.heartbeat.feature.organicai.api.GrowthLimits
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
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
        val organisms = StudioOrganisms(Registry(machine), selections)
        val outcome = organisms.submit(chat, "Build it", settings, emptyList()) { accepted++ }
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
    fun `an ordinary chat is not an organism and an organism retains its goal inputs`() = runTest {
        val machine = OrganicMachine(OrganicAiState.Living())
        val organisms = StudioOrganisms(Registry(machine), selections)
        assertNull(organisms.submit(chat.copy(organismId = null), "Hi", settings, emptyList()) {})
        val files = listOf(ResourceRef("attachment:image", "image/png"))
        assertEquals(RunOutcome.Completed, organisms.submit(chat, "", settings, files) {})
        val conception = (machine.sent.single() as OrganicAiIntent.Public.Conceive).conception
        assertEquals(files, conception.attachments)
        assertEquals("", conception.goal)
    }

    @Test
    fun `an organism chat is admitted only when its organism could be conceived`() = runTest {
        val organisms = { machine: OrganicMachine? -> StudioOrganisms(Registry(machine), selections) }
        val living = organisms(OrganicMachine(OrganicAiState.Living()))
        val request = OrganismRequest("Build", settings)
        assertEquals("chat-2", living.admit("chat-2", request, isWorktree = false))
        val long = request.copy(goal = "x".repeat(MAX_GOAL + 1))
        assertFailsWith<IllegalArgumentException> { living.admit("chat-2", long, isWorktree = false) }
        assertFailsWith<IllegalArgumentException> { living.admit("chat-2", request, isWorktree = true) }
        assertFailsWith<IllegalStateException> { organisms(null).admit("chat-2", request, isWorktree = false) }
        val broken = organisms(OrganicMachine(OrganicAiState.Broken))
        assertFailsWith<IllegalStateException> { broken.admit("chat-2", request, isWorktree = false) }
    }

    @Test
    fun `a rejected follow-up never acknowledges or conceives another organism`() = runTest {
        val machine = OrganicMachine(OrganicAiState.Living(mapOf(OrganismId("chat-1") to organism("chat-1"))))
            .apply { result = SendResult.Ignored }
        val organisms = StudioOrganisms(Registry(machine), selections)
        var accepted = false
        assertFailsWith<IllegalStateException> {
            organisms.submit(chat, "More", settings, emptyList()) { accepted = true }
        }
        assertEquals(false, accepted)
        assertEquals(
            listOf<OrganicAiIntent.Public>(OrganicAiIntent.Public.FollowUp(OrganismId(chat.id), "More")),
            machine.sent,
        )
    }

    @Test
    fun `a completed organism takes a message and inputs in the existing chat`() = runTest {
        val completed = organism(chat.id).copy(status = OrganismStatus.Completed("done"))
        val machine = OrganicMachine(OrganicAiState.Living(mapOf(completed.id to completed)))
        val organisms = StudioOrganisms(Registry(machine), selections)
        val inputs = listOf(ResourceRef("attachment:next", "image/png"))
        var accepted = 0
        assertEquals(RunOutcome.Completed, organisms.submit(chat, "More", settings, inputs) { accepted++ })
        assertEquals(1, accepted)
        assertEquals(
            listOf<OrganicAiIntent.Public>(OrganicAiIntent.Public.FollowUp(completed.id, "More", inputs)),
            machine.sent,
        )
    }

    @Test
    fun `conception fails while organic AI is off, broken or refuses`() = runTest {
        val organisms = { machine: OrganicMachine? -> StudioOrganisms(Registry(machine), selections) }
        assertFailsWith<IllegalStateException> { organisms(null).submit(chat, "Build", settings, emptyList()) {} }
        assertFailsWith<IllegalStateException> {
            organisms(OrganicMachine(OrganicAiState.Broken)).submit(chat, "Build", settings, emptyList()) {}
        }
        val refusing = OrganicMachine(OrganicAiState.Living()).apply { result = SendResult.Ignored }
        assertFailsWith<IllegalStateException> { organisms(refusing).submit(chat, "Build", settings, emptyList()) {} }
    }

    @Test
    fun `viewed reasoning streams until the turn ends and context respects the telemetry gate`() = runTest {
        val ref = SessionRef(EngineId("pi"), SessionSourceId("local"), "cell")
        val items = listOf(
            SessionItem.Message(
                ItemInfo(ItemId("r"), 0, 0),
                MessageRole.Assistant,
                listOf(ContentPart.Reasoning("Checking the goal")),
            ),
        )
        val facade = ViewedFacade(ref, items, hasTreeHistory = true)
        val allowed = MutableStateFlow(true)
        val viewer = StudioSessionViewer(facade, MemoryTranscriptDao().transcripts()) { allowed }
        val route = ExecutionRoute(ref.engine, reopening.target.binding, AuthSourceId("auth"), AuthRevision.Known("1"))
        facade.observations.value = SessionObservationSnapshot(
            ActiveSessionState.Running(Turn(TurnId("turn"), null, reopening.target)),
            route,
            ContextUsage(40, 100),
        )
        var messages = emptyList<StudioMessage>()
        var snapshot: SessionObservationSnapshot? = null
        val reader = backgroundScope.launch { viewer.observe(ref, reopening).collect { messages = it } }
        backgroundScope.launch { viewer.observation(ref).collect { snapshot = it } }
        runCurrent()
        val reasoning = messages.single() as StudioMessage.Reply
        assertTrue(reasoning.isStreaming)
        assertEquals("Checking the goal", (reasoning.parts.single() as StudioReplyPart.Reasoning).text)
        assertEquals(ContextUsage(40, 100), snapshot?.context)

        allowed.value = false
        runCurrent()
        assertNull(snapshot?.context)
        facade.observations.value = facade.observations.value?.copy(state = ActiveSessionState.Ready())
        runCurrent()
        assertEquals(false, (messages.single() as StudioMessage.Reply).isStreaming)
        reader.cancelAndJoin()
        assertEquals(emptyList(), facade.resumptions)
        assertEquals(0, facade.closes)
    }

    @Test
    fun `viewed observation recovers the same session after an unavailable engine and cancels retries`() = runTest {
        val ref = SessionRef(EngineId("pi"), SessionSourceId("local"), "cell")
        val facade = ViewedFacade(ref, emptyList())
        var isAvailable = false
        var attempts = 0
        val recovering = object : EngineFacade by facade {
            override val sessions = object : SessionCatalog by facade.sessions {
                override suspend fun get(ref: SessionRef): EngineSession {
                    attempts++
                    check(isAvailable) { "Engine unavailable" }
                    return facade.sessions.get(ref)
                }
            }
        }
        val viewer = StudioSessionViewer(recovering, MemoryTranscriptDao().transcripts()) { flowOf(true) }
        val route = ExecutionRoute(ref.engine, reopening.target.binding, AuthSourceId("auth"), AuthRevision.Known("1"))
        val running = SessionObservationSnapshot(
            ActiveSessionState.Running(Turn(TurnId("turn"), null, reopening.target)),
            route,
            ContextUsage(40, 100),
        )
        facade.observations.value = running
        val observed = mutableListOf<SessionObservationSnapshot?>()
        val reader = backgroundScope.launch { viewer.observation(ref).collect { observed += it } }
        runCurrent()
        assertEquals(listOf<SessionObservationSnapshot?>(null), observed)
        assertEquals(1, attempts)
        isAvailable = true
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(running, observed.last())
        assertEquals(2, attempts)
        reader.cancelAndJoin()
        assertEquals(0, facade.observations.subscriptionCount.value)

        isAvailable = false
        val waiting = backgroundScope.launch { viewer.observation(ref).collect {} }
        runCurrent()
        assertEquals(3, attempts)
        waiting.cancelAndJoin()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(3, attempts)
        assertEquals(emptyList(), facade.resumptions)
        assertEquals(0, facade.closes)
    }

    @Test
    fun `a viewed session shows its history`() = runTest {
        val ref = SessionRef(EngineId("pi"), SessionSourceId("local"), "cell")
        val history = listOf(
            SessionItem.Message(ItemInfo(ItemId("p"), 0, 0), MessageRole.User, listOf(ContentPart.Text("Task"))),
            SessionItem.Message(ItemInfo(ItemId("r"), 1, 0), MessageRole.Assistant, listOf(ContentPart.Text("Done"))),
        )
        val facade = ViewedFacade(ref, history)
        val viewer = StudioSessionViewer(facade, MemoryTranscriptDao().transcripts())
        val messages = viewer.observe(ref, reopening).first { it.size == 2 }
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
        val viewer = StudioSessionViewer(facade, MemoryTranscriptDao().transcripts())
        val messages = viewer.observe(ref, reopening).first { it.size == 1 }
        assertEquals("Task", (messages.single() as StudioMessage.Prompt).text)
        assertEquals(listOf(reopening), facade.resumptions)
        assertEquals(1, facade.closes)
    }

    @Test
    fun `an unreadable viewed session stays empty instead of failing the screen`() = runTest {
        val ref = SessionRef(EngineId("pi"), SessionSourceId("local"), "gone")
        val viewer = StudioSessionViewer(ViewedFacade(ref = null, emptyList()), MemoryTranscriptDao().transcripts())
        val messages = viewer.observe(ref, reopening).first()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `unviewed transcript survives a new viewer and empty partial native replay`() = runTest {
        val ref = SessionRef(EngineId("codex"), SessionSourceId("local"), "cell")
        val items = listOf(
            SessionItem.Message(ItemInfo(ItemId("p"), 0, 0), MessageRole.User, listOf(ContentPart.Text("Task"))),
            SessionItem.Message(ItemInfo(ItemId("r"), 1, 0), MessageRole.Assistant, listOf(ContentPart.Text("Done"))),
        )
        val dao = MemoryTranscriptDao()
        StudioSessionViewer(ViewedFacade(ref, items), dao.transcripts()).record(ref, reopening, isLive = false)
        val resumed = ViewedFacade(ref, emptyList(), coverage = HistoryCoverage.Partial)
        val viewer = StudioSessionViewer(resumed, dao.transcripts())
        viewer.record(ref, reopening, isLive = false)

        val messages = viewer.observe(ref, reopening).first { it.size == 2 }
        assertEquals("Done", (messages.last() as StudioMessage.Reply).text)
    }

    @Test
    fun `native ids from different history sources never share a transcript`() = runTest {
        val ref = SessionRef(EngineId("codex"), SessionSourceId("first"), "same-id")
        val other = ref.copy(source = SessionSourceId("second"))
        val items = listOf(
            SessionItem.Message(ItemInfo(ItemId("r"), 0, 0), MessageRole.Assistant, listOf(ContentPart.Text("First"))),
        )
        val dao = MemoryTranscriptDao()
        StudioSessionViewer(ViewedFacade(ref, items), dao.transcripts()).record(ref, reopening, isLive = false)
        val viewer = StudioSessionViewer(
            ViewedFacade(other, emptyList(), coverage = HistoryCoverage.Partial),
            dao.transcripts(),
        )
        viewer.record(other, reopening, isLive = false)

        assertTrue(viewer.observe(other, reopening).first().isEmpty())
    }

    @Test
    fun `two observers share one native reader and its cancellation saves a final snapshot`() = runTest {
        val ref = SessionRef(EngineId("codex"), SessionSourceId("local"), "cell")
        val facade = ViewedFacade(ref, emptyList(), hasStoredHistory = false)
        val dao = MemoryTranscriptDao()
        val viewer = StudioSessionViewer(facade, dao.transcripts())
        val first = backgroundScope.launch { viewer.observe(ref, reopening).collect {} }
        val second = backgroundScope.launch { viewer.observe(ref, reopening).collect {} }
        runCurrent()
        assertEquals(1, facade.resumptions.size)
        facade.items = listOf(
            SessionItem.Message(ItemInfo(ItemId("r"), 0, 0), MessageRole.Assistant, listOf(ContentPart.Text("Final"))),
        )
        second.cancelAndJoin()
        first.cancelAndJoin()
        assertEquals(1, facade.closes)

        val resumed = StudioSessionViewer(
            ViewedFacade(ref, emptyList(), coverage = HistoryCoverage.Partial),
            dao.transcripts(),
        )
        val answer = resumed.observe(ref, reopening).first { it.isNotEmpty() }.single() as StudioMessage.Reply
        assertEquals("Final", answer.text)
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
    var items: List<SessionItem>,
    private val hasStoredHistory: Boolean = true,
    private val coverage: HistoryCoverage = HistoryCoverage.Complete,
    private val hasTreeHistory: Boolean = false,
) : EngineFacade {
    val resumptions = mutableListOf<ResumeSessionRequest>()
    var closes = 0
    val observations = MutableStateFlow<SessionObservationSnapshot?>(null)
    private val observation = object : SessionObservation {
        override val snapshots = observations
    }

    override val engines: EngineCatalog = object : EngineCatalog {
        override val state = MutableStateFlow<List<EngineInfo>>(
            emptyList(),
        )
        override suspend fun refresh(engine: EngineId) = error("unused")
        override fun features(engine: EngineId): EngineFeatures = object : EngineFeatures {
            @Suppress("UNCHECKED_CAST") // The fake offers only the typed tree capability.
            override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
                if (key == SessionTrees && hasTreeHistory) {
                    FeatureAccess.Available(trees as F)
                } else {
                    FeatureAccess.Unsupported
                }
        }
    }
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
                        key == SessionObservation -> FeatureAccess.Available(observation as F)
                        key == SessionHistory && hasStoredHistory -> FeatureAccess.Available(history as F)
                        key == ResumesSessions && !hasStoredHistory -> FeatureAccess.Available(resumes as F)
                        else -> FeatureAccess.Unsupported
                    }
                }
            }
        }
    }

    private val trees = object : SessionTrees {
        override fun observe(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot> = emptyFlow()

        override suspend fun history(root: SessionRef, ref: SessionRef, access: SessionTreeAccess): SessionHistory =
            error("Live stored history must take precedence over tree replay")
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
            HistoryPage(items, null, null, HistoryCheckpoint("checkpoint"), coverage)

        override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow { awaitCancellation() }
    }
}

private val MAX_GOAL = OrganismBounds.MAX_GOAL

/** The smallest organism: a zygote alone. */
private fun organism(id: String) = Organism(
    OrganismId(id),
    "Build",
    target = null,
    immunityTarget = null,
    workspace = null,
    trust = null,
    limits = GrowthLimits(),
    cells = listOf(Cell(CellId.ZYGOTE, "zygote", null, "Build", CellPhase.Resting)),
)
