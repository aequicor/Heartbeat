package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.OwnedTurnStop
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.StopsOwnedTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.reflect.safeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class StudioHelperNativeTest {
    @Test
    fun `restored terminal extracts only its marked answer and ignores a later unrelated message`() = runTest {
        val fixture = NativeHelperFixture()
        fixture.stop = OwnedTurnStop.Confirmed(fixture.turn)
        fixture.messages = listOf(
            fixture.message("own", "exact answer", fixture.turn.id),
            fixture.message("foreign", "other", TurnId("other")),
        )
        val result = fixture.native.result(fixture.record, fixture.turn.request!!) { fixture }
        assertEquals("exact answer", result?.answer)
        assertEquals(fixture.turn.id, result?.turn)
        assertNull(fixture.native.result(fixture.record, RequestId("other")) { fixture })
        fixture.state.value = ActiveSessionState.Running(fixture.turn.copy(outcome = null))
        assertNull(fixture.native.result(fixture.record, fixture.turn.request!!) { fixture })
    }

    @Test
    fun `native terminal is neither saved nor exposed while adapter hosted drain is unconfirmed`() = runTest {
        val fixture = NativeHelperFixture()
        fixture.prepare()
        fixture.messages = listOf(fixture.message("own", "answer", fixture.turn.id))
        assertNull(fixture.native.result(fixture.record, fixture.turn.request!!) { fixture })
        fixture.native.remember(
            fixture.record,
            fixture.turn.request!!,
            fixture.ref,
            fixture.turn.id,
            TurnOutcome.Completed,
            fixture.messages,
        )
        assertNull(fixture.attempts.receipt(HelperId("helper"), fixture.turn.request!!)?.terminal)
        fixture.stop = OwnedTurnStop.Confirmed(fixture.turn)
        fixture.native.remember(
            fixture.record,
            fixture.turn.request!!,
            fixture.ref,
            fixture.turn.id,
            TurnOutcome.Completed,
            fixture.messages,
        )
        assertEquals("answer", fixture.attempts.receipt(HelperId("helper"), fixture.turn.request!!)?.terminal?.answer)
    }

    @Test
    fun `unsupported owned stop still accepts authoritative terminal for synchronous adapters`() = runTest {
        val fixture = NativeHelperFixture()
        fixture.supportsStop = false
        fixture.messages = listOf(fixture.message("own", "answer", fixture.turn.id))
        assertEquals("answer", fixture.native.result(fixture.record, fixture.turn.request!!) { fixture }?.answer)
    }

    @Test
    fun `cold stop sends exact route request and expected turn without opening a session`() = runTest {
        val fixture = NativeHelperFixture()
        fixture.prepare()
        fixture.stop = OwnedTurnStop.Confirmed(fixture.turn.copy(outcome = TurnOutcome.Unknown))
        val result = fixture.native.stop(fixture.record, fixture.turn.request!!, active = null)
        assertEquals(StudioHelperTerminalOutcome.Unknown, result?.outcome)
        assertEquals(
            listOf(fixture.ref, fixture.turn.request, OwnedTurnAccess(fixture.target, expectedTurn = fixture.turn.id)),
            fixture.stopped,
        )
    }

    @Test
    fun `missing capability or unconfirmed stop cannot manufacture terminal evidence`() = runTest {
        val fixture = NativeHelperFixture()
        fixture.prepare()
        assertNull(fixture.native.stop(fixture.record, fixture.turn.request!!, active = null))
        fixture.supportsStop = false
        assertNull(fixture.native.stop(fixture.record, fixture.turn.request!!, active = null))
        assertNull(fixture.native.stop(fixture.record, RequestId("foreign"), active = fixture))
    }

    @Test
    fun `foreign stop result never settles the owned receipt`() = runTest {
        val fixture = NativeHelperFixture()
        fixture.prepare()
        fixture.stop = OwnedTurnStop.Confirmed(fixture.turn.copy(request = RequestId("foreign")))
        assertNull(fixture.native.stop(fixture.record, fixture.turn.request!!, active = null))
        assertEquals(
            StudioHelperPhase.Accepted,
            fixture.attempts.receipt(HelperId("helper"), fixture.turn.request!!)?.phase,
        )
    }
}

private class NativeHelperFixture :
    ActiveSession,
    SessionHistory {
    val attempts = StudioHelperAttempts(ChecklistTestStores())
    val target = EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("model"))
    val turn = Turn(TurnId("turn"), RequestId("request"), target, TurnOutcome.Completed)
    override val ref = SessionRef(target.engine, SessionSourceId("local"), "native")
    override val route = ExecutionRoute(
        target.engine,
        target.binding,
        AuthSourceId("source"),
        AuthRevision.Known("revision"),
    )
    override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready(turn))
    override val features = nativeFeatures(this)
    var messages = emptyList<SessionItem>()
    var supportsStop = true
    var stop: OwnedTurnStop = OwnedTurnStop.Unconfirmed
    var stopped: List<Any?>? = null
    val record = StudioChatRecord(
        "helper",
        "Helper",
        Instant.fromEpochMilliseconds(0),
        ref = ref,
        target = target,
        helper = StudioHelperIdentity(ActionId("wf_owner"), null, null, TrustLevel.Ask),
    )
    private val stopper = object : StopsOwnedTurns {
        override suspend fun stop(ref: SessionRef, request: RequestId, access: OwnedTurnAccess): OwnedTurnStop {
            stopped = listOf(ref, request, access)
            return stop
        }
    }
    private val facade = object : EngineFacade {
        override val engines = object : EngineCatalog {
            override val state = MutableStateFlow<List<EngineInfo>>(emptyList())
            override suspend fun refresh(engine: EngineId): EngineInfo = error("No discovery")
            override fun features(engine: EngineId): EngineFeatures = nativeFeatures(
                *if (supportsStop) arrayOf(stopper) else emptyArray(),
            )
        }
        override val sessions: SessionCatalog get() = error("No create or resume")
        override val bindings: EngineBindings get() = error("No binding changes")
        override val models: ModelCatalog get() = error("No model discovery")
        override val providerUsage: ProviderUsageCatalog get() = error("No usage discovery")
    }
    val native = StudioHelperNative(facade, NoAgentTools, attempts)

    suspend fun prepare() {
        val helper = HelperId(record.id)
        attempts.prepare(helper, HelperPrompt(turn.request!!, "prompt"))
        attempts.begin(helper, turn.request!!)
        attempts.accepted(helper, turn.request!!, ref, turn.id)
    }

    fun message(id: String, text: String, turn: TurnId) = SessionItem.Message(
        ItemInfo(ItemId(id), if (id == "own") 0 else 1, 0, turn),
        MessageRole.Assistant,
        listOf(ContentPart.Text(text)),
    )

    override suspend fun close() = Unit
    override suspend fun page(request: HistoryPageRequest) =
        HistoryPage(messages, null, null, HistoryCheckpoint("checkpoint"), HistoryCoverage.Complete)
    override fun watch(after: HistoryCheckpoint) = emptyFlow<SessionEvent>()
}

private fun nativeFeatures(vararg entries: EngineFeature) = object : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
        entries.firstNotNullOfOrNull { key.type.safeCast(it) }?.let { FeatureAccess.Available(it) }
            ?: FeatureAccess.Unsupported
}
