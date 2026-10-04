package io.aequicor.heartbeat.feature.aistudio.impl.data

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
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.organicai.api.Conception
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
        val outcome = StudioOrganisms(Registry(machine), selections).conceive(chat, "Build it", settings) { accepted++ }
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
    fun `an ordinary chat is not an organism`() = runTest {
        val machine = OrganicMachine(OrganicAiState.Living())
        val ordinary = chat.copy(organismId = null)
        assertNull(StudioOrganisms(Registry(machine), selections).conceive(ordinary, "Hi", settings) {})
        assertEquals(emptyList(), machine.sent)
    }

    @Test
    fun `conception fails while organic AI is off, broken or refuses`() = runTest {
        val organisms = { machine: OrganicMachine? -> StudioOrganisms(Registry(machine), selections) }
        assertFailsWith<IllegalStateException> { organisms(null).conceive(chat, "Build", settings) {} }
        assertFailsWith<IllegalStateException> {
            organisms(OrganicMachine(OrganicAiState.Broken)).conceive(chat, "Build", settings) {}
        }
        val refusing = OrganicMachine(OrganicAiState.Living()).apply { result = SendResult.Ignored }
        assertFailsWith<IllegalStateException> { organisms(refusing).conceive(chat, "Build", settings) {} }
    }

    @Test
    fun `a viewed session shows its history`() = runTest {
        val ref = SessionRef(EngineId("pi"), SessionSourceId("local"), "cell")
        val history = listOf(
            SessionItem.Message(ItemInfo(ItemId("p"), 0, 0), MessageRole.User, listOf(ContentPart.Text("Task"))),
            SessionItem.Message(ItemInfo(ItemId("r"), 1, 0), MessageRole.Assistant, listOf(ContentPart.Text("Done"))),
        )
        val messages = StudioSessionViewer(ViewedFacade(ref, history)).observe(ref).first { it.size == 2 }
        assertEquals("Task", (messages[0] as StudioMessage.Prompt).text)
        assertEquals("Done", (messages[1] as StudioMessage.Reply).text)
    }

    @Test
    fun `an unreadable viewed session stays empty instead of failing the screen`() = runTest {
        val ref = SessionRef(EngineId("pi"), SessionSourceId("local"), "gone")
        val messages = StudioSessionViewer(ViewedFacade(ref = null, emptyList())).observe(ref).first()
        assertTrue(messages.isEmpty())
    }
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

/** A facade storing one session [ref] with [items] in its history; any other session cannot be read. */
private class ViewedFacade(private val ref: SessionRef?, private val items: List<SessionItem>) : EngineFacade {
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
                    @Suppress("UNCHECKED_CAST") // The fake offers only its history.
                    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
                        if (key == SessionHistory) FeatureAccess.Available(history as F) else FeatureAccess.Unsupported
                }
            }
        }
    }

    private val history = object : SessionHistory {
        override suspend fun page(request: HistoryPageRequest): HistoryPage =
            HistoryPage(items, null, null, HistoryCheckpoint("checkpoint"), HistoryCoverage.Complete)

        override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow { awaitCancellation() }
    }
}
