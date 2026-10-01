package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.SourceRef
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.Target
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.TargetRef
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.text
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FacadeAdaptersTest {
    private val unavailable = EngineFailure.Engine(EngineFailureReason.Unavailable)

    @Test
    fun `missing facade reports the engine as unavailable`() = runTest {
        assertEquals(
            unavailable,
            assertFailsWith<EngineException> { FacadeSessionTranscripts().read(SourceRef, 10) }.failure,
        )
        assertEquals(
            unavailable,
            assertFailsWith<EngineException> { FacadeEngineSessions().create(Target, null) }.failure,
        )
    }

    @Test
    fun `history is read from the newest window backwards within the budget`() = runTest {
        val history = PagedHistory(pages = 5, coverage = HistoryCoverage.Complete)
        val transcripts = FacadeSessionTranscripts(historyFacade(FeatureAccess.Available(history)))

        val all = transcripts.read(SourceRef, budgetChars = 1_000_000)
        assertEquals((0L until 10L).toList(), all.items.map { it.info.position })
        assertFalse(all.isTruncated)
        assertEquals(HistoryCoverage.Complete, all.coverage)

        val newest = transcripts.read(SourceRef, budgetChars = 1)
        assertEquals(listOf(8L, 9L), newest.items.map { it.info.position })
        assertTrue(newest.isTruncated)
    }

    @Test
    fun `partial native coverage is reported`() = runTest {
        val history = PagedHistory(pages = 1, coverage = HistoryCoverage.Partial)
        val transcript = FacadeSessionTranscripts(historyFacade(FeatureAccess.Available(history))).read(SourceRef, 100)
        assertEquals(HistoryCoverage.Partial, transcript.coverage)
    }

    @Test
    fun `missing or blocked history is a failure never an empty transcript`() = runTest {
        val unsupported = assertFailsWith<EngineException> {
            FacadeSessionTranscripts(historyFacade(FeatureAccess.Unsupported)).read(SourceRef, 100)
        }
        assertEquals(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability), unsupported.failure)
        val blocked = EngineFailure.History(HistoryFailureReason.Unavailable)
        val failure = assertFailsWith<EngineException> {
            FacadeSessionTranscripts(historyFacade(FeatureAccess.Unavailable(blocked))).read(SourceRef, 100)
        }
        assertEquals(blocked, failure.failure)
    }

    @Test
    fun `target sessions are created on the exact route and submit through the handle`() = runTest {
        val prompts = mutableListOf<PromptRequest>()
        val sends = object : SendsPrompts {
            override suspend fun send(request: PromptRequest): TurnId {
                prompts += request
                return TurnId("turn")
            }
        }
        val active = FakeActiveSession(TargetRef, FakeFeatures(mapOf(SendsPrompts to FeatureAccess.Available(sends))))
        val requests = mutableListOf<CreateSessionRequest>()
        val creates = object : CreatesSessions {
            override suspend fun create(request: CreateSessionRequest) = active.also { requests += request }
        }
        val facade = FakeEngineFacade(
            engineFeatures = mapOf(
                Target.engine to FakeFeatures(mapOf(CreatesSessions to FeatureAccess.Available(creates))),
            ),
        )

        val session = FacadeEngineSessions(facade).create(Target, null)
        val prompt = PromptRequest(RequestId("handoff"), listOf(ContentPart.Text("transcript")))
        session.send(prompt)
        session.close()

        assertEquals(listOf(CreateSessionRequest(Target, null)), requests)
        assertEquals(TargetRef, session.ref)
        assertEquals(listOf(prompt), prompts)
        assertEquals(1, active.closes)
    }

    @Test
    fun `engines without session creation are rejected`() = runTest {
        val facade = FakeEngineFacade(engineFeatures = mapOf(Target.engine to FakeFeatures(emptyMap())))
        val failure = assertFailsWith<EngineException> { FacadeEngineSessions(facade).create(Target, null) }
        assertEquals(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability), failure.failure)
    }

    @Test
    fun `created sessions that cannot accept prompts are released not orphaned`() = runTest {
        val active = FakeActiveSession(TargetRef, FakeFeatures(emptyMap()))
        val creates = object : CreatesSessions {
            override suspend fun create(request: CreateSessionRequest) = active
        }
        val facade = FakeEngineFacade(
            engineFeatures = mapOf(
                Target.engine to FakeFeatures(mapOf(CreatesSessions to FeatureAccess.Available(creates))),
            ),
        )

        val failure = assertFailsWith<EngineException> { FacadeEngineSessions(facade).create(Target, null) }

        assertEquals(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability), failure.failure)
        assertEquals(1, active.closes)
    }

    private fun historyFacade(access: FeatureAccess<SessionHistory>) =
        FakeEngineFacade(storedSessions = mapOf(SourceRef to FakeFeatures(mapOf(SessionHistory to access))))

    /** [pages] windows of two messages each; the first request returns the newest window. */
    private class PagedHistory(private val pages: Int, private val coverage: HistoryCoverage) : SessionHistory {
        override suspend fun page(request: HistoryPageRequest): HistoryPage {
            val index = request.cursor?.value?.toInt() ?: (pages - 1)
            val items = listOf(2L * index, 2L * index + 1).map { text(it, MessageRole.User, "message $it") }
            return HistoryPage(
                items = items,
                older = if (index > 0) HistoryCursor((index - 1).toString()) else null,
                newer = null,
                checkpoint = HistoryCheckpoint("checkpoint"),
                coverage = coverage,
            )
        }

        override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = emptyFlow()
    }
}
