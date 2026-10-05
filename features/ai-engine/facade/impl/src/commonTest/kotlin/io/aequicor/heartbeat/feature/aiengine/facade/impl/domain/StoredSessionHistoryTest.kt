package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class StoredSessionHistoryTest {
    private val handles = ActiveSessionRegistry()
    private val capabilities = FacadeCapabilities(UnusedCatalog, lazy { error("Viewing must not resume") }, handles)
    private val ref = sessionRef("cell")
    private val live = LiveHistory()
    private val native = FakeNativeSession(ref, ActiveSessionState.Running(Turn(TurnId("turn"), null, TestTarget)))

    private fun add(ref: SessionRef = this.ref): ActiveSession = object : ActiveSession by native {
        override val ref = ref
        override val features: EngineFeatures = FeatureTable(mapOf(SessionHistory.id to available(live)))
    }.also(handles::add)

    private fun stored(features: EngineFeatures = NoEngineFeatures) = capabilities.stored(
        registration(),
        FakeStoredSession(SessionSummary(ref), features),
    )

    @Test
    fun `viewing a running cell streams reasoning without resuming or closing its handle`() = runTest {
        add()
        val stored = stored()
        val history = assertIs<FeatureAccess.Available<SessionHistory>>(stored.features.resolve(SessionHistory)).feature
        val page = history.page()
        val observed = mutableListOf<SessionEvent>()
        val reader = launch { history.watch(page.checkpoint).collect { observed += it } }
        runCurrent()
        val reasoning = SessionItem.Message(
            ItemInfo(ItemId("reasoning"), 0, 1),
            MessageRole.Assistant,
            listOf(ContentPart.Reasoning("Checking the goal")),
        )
        val event = SessionEvent.ItemUpserted(HistoryCheckpoint("1"), reasoning)
        live.events.emit(event)
        runCurrent()
        reader.cancel()
        reader.join()

        assertEquals<List<SessionEvent>>(listOf(event), observed)
        assertIs<ActiveSessionState.Running>(native.state.value)
        assertEquals(0, native.closes)
        assertEquals(FeatureAccess.Unsupported, stored.features.resolve(SendsPrompts))
    }

    @Test
    fun `a stored handle resolves live history when opened and falls back after closing`() = runTest {
        val archived = LiveHistory()
        val stored = stored(FeatureTable(mapOf(SessionHistory.id to available(archived))))
        assertSame(archived, stored.features.resolve(SessionHistory).orFail())
        val handle = add()
        assertSame(live, stored.features.resolve(SessionHistory).orFail())
        native.native.value = ActiveSessionState.Closing()
        assertSame(archived, stored.features.resolve(SessionHistory).orFail())
        native.close()
        handles.remove(handle)
        assertSame(archived, stored.features.resolve(SessionHistory).orFail())
    }

    @Test
    fun `matching native ids from different engines or sources never provide cell history`() {
        add(ref.copy(engine = EngineId("other")))
        add(ref.copy(source = SessionSourceId("other")))
        assertEquals(FeatureAccess.Unsupported, stored().features.resolve(SessionHistory))
    }

    @Test
    fun `closed handles cannot supply history before registry removal`() = runTest {
        add()
        native.close()
        assertEquals(FeatureAccess.Unsupported, stored().features.resolve(SessionHistory))
    }
}

private class LiveHistory : SessionHistory {
    val events = MutableSharedFlow<SessionEvent>()
    override suspend fun page(request: HistoryPageRequest) =
        HistoryPage(emptyList(), null, null, HistoryCheckpoint("0"), HistoryCoverage.Complete)

    override fun watch(after: HistoryCheckpoint) = events
}

private object UnusedCatalog : SessionCatalog {
    override suspend fun page(query: SessionQuery, request: PageRequest) = error("unused")
    override suspend fun get(ref: SessionRef) = error("unused")
    override suspend fun refresh(query: SessionQuery) = error("unused")
}
