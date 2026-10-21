package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnInspection
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitch
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchFailure
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitchState
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitching
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudioEngineSwitcherTest {
    @Test
    fun `existing local source requires a confirmed terminal receipt including after restoration`() = runTest {
        for (outcome in listOf(null, TurnOutcome.Completed, TurnOutcome.Cancelled, TurnOutcome.Unknown)) {
            val fixture = SwitchFixture(this)
            val active = ConfigurationSession(fixture.source, "native", null)
            active.state.value = ActiveSessionState.Ready(
                outcome?.let {
                    Turn(TurnId("last"), RequestId("request"), fixture.source, outcome = it)
                },
            )
            fixture.host.active = active
            fixture.host.record = fixture.host.record.copy(ref = active.ref)
            fixture.switcher.switch("chat", fixture.change)
            val allowed = outcome != null && outcome != TurnOutcome.Unknown
            assertEquals(if (allowed) null else StudioEngineSwitchFailure.Busy, fixture.host.state.failure)
            assertEquals(if (allowed) fixture.change.target else fixture.source, fixture.host.record.target)
            assertEquals(allowed, fixture.host.wasRefreshed)
        }
    }

    @Test
    fun `remote source inspection overrides a cached completed state and preserves uncertain route`() = runTest {
        for (inspection in listOf(
            TurnInspection.Idle,
            TurnInspection.Unknown,
            TurnInspection.Observed(RequestId("other"), null),
        )) {
            val fixture = SwitchFixture(this)
            val session = ConfigurationSession(fixture.source, "native", null)
            session.state.value = ActiveSessionState.Ready(
                Turn(TurnId("last"), RequestId("request"), fixture.source, outcome = TurnOutcome.Completed),
            )
            fixture.host.active = object : ActiveSession by session {
                override val features = inspectionFeatures(inspection)
            }
            fixture.host.record = fixture.host.record.copy(ref = session.ref)
            fixture.switcher.switch("chat", fixture.change)
            assertEquals(inspection == TurnInspection.Idle, fixture.host.wasRefreshed)
            assertEquals(
                if (inspection == TurnInspection.Idle) null else StudioEngineSwitchFailure.Busy,
                fixture.host.state.failure,
            )
        }
    }

    @Test
    fun `same connection model replacement keeps settings and requires confirmed idle`() = runTest {
        for (inspection in listOf(TurnInspection.Idle, TurnInspection.Unknown)) {
            val fixture = SwitchFixture(this)
            val change = fixture.change.copy(target = fixture.source.copy(model = ModelId("other")))
            val session = ConfigurationSession(fixture.source, "native", null)
            fixture.host.active = object : ActiveSession by session {
                override val features = inspectionFeatures(inspection)
            }
            fixture.host.record = fixture.host.record.copy(ref = session.ref)
            fixture.switcher.switch("chat", change)
            val allowed = inspection == TurnInspection.Idle
            assertEquals(if (allowed) null else StudioEngineSwitchFailure.Busy, fixture.host.state.failure)
            assertEquals(if (allowed) change.target else fixture.source, fixture.host.record.target)
            assertEquals(ApprovalMode.AutoEdits, fixture.host.record.configuration?.approval)
            assertEquals(allowed, fixture.host.wasRefreshed)
        }
    }

    @Test
    fun `screen cancellation leaves profile replacement owned and completion precedes releasing its slot`() = runTest {
        val fixture = SwitchFixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.host.commit = { gate.await() }
        var isCompletionReserved = false
        fixture.host.onState = { state ->
            if (!state.isPending) {
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<IllegalStateException> { fixture.runs.mutate("chat") { } }
                    isCompletionReserved = true
                }
            }
        }
        val caller = async { fixture.switcher.switch("chat", fixture.change) }
        runCurrent()
        assertTrue(fixture.host.state.isPending)
        caller.cancelAndJoin()
        assertTrue(fixture.host.state.isPending)
        gate.complete(Unit)
        runCurrent()
        assertEquals(fixture.change.target, fixture.host.record.target)
        assertFalse(fixture.host.state.isPending)
        assertTrue(isCompletionReserved)
        assertEquals("low", fixture.host.record.configuration?.reasoningEffort)
        assertEquals(ApprovalMode.AutoEdits, fixture.host.record.configuration?.approval)
    }

    @Test
    fun `disabled stale organic and reserved requests never commit a route`() = runTest {
        val fixture = SwitchFixture(this)
        fixture.toggles.isEnabled = false
        fixture.switcher.switch("chat", fixture.change)
        assertEquals(StudioEngineSwitchFailure.Disabled, fixture.host.state.failure)
        fixture.toggles.isEnabled = true
        fixture.switcher.switch("chat", fixture.change.copy(expected = fixture.change.target))
        assertEquals(StudioEngineSwitchFailure.Stale, fixture.host.state.failure)
        fixture.host.record = fixture.host.record.copy(organismId = "organism")
        fixture.switcher.switch("chat", fixture.change)
        assertEquals(StudioEngineSwitchFailure.Unsupported, fixture.host.state.failure)
        fixture.host.record = fixture.host.record.copy(organismId = null)
        fixture.runs.mutate("chat") { fixture.switcher.switch("chat", fixture.change) }
        assertEquals(StudioEngineSwitchFailure.Busy, fixture.host.state.failure)
        assertEquals(fixture.change.expected, fixture.host.record.target)
    }

    @Test
    fun `second request and disabled toggle cannot hide the current pending operation`() = runTest {
        val fixture = SwitchFixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.host.commit = { gate.await() }
        val first = async { fixture.switcher.switch("chat", fixture.change) }
        runCurrent()
        fixture.switcher.switch("chat", fixture.change)
        assertTrue(fixture.host.state.isPending)
        fixture.toggles.isEnabled = false
        fixture.switcher.switch("chat", fixture.change)
        assertTrue(fixture.host.state.isPending)
        gate.complete(Unit)
        first.await()
        assertFalse(fixture.host.state.isPending)
    }
}

private class SwitchFixture(scope: TestScope) {
    val source = EngineTarget(EngineId("a"), EngineBindingId("binding"), ModelId("model"))
    val change = StudioEngineSwitch(source, source.copy(engine = EngineId("b")))
    val toggles = SwitchToggles()
    val host = SwitchHost(source)
    val profile = RunProfile(scope.backgroundScope)
    val runs = StudioRunCoordinator(profile, RunClock)
    val switcher = StudioEngineSwitcher(profile, toggles, runs, StudioConfigurationFixture(scope).controller, host)
}

private class SwitchHost(source: EngineTarget) : StudioEngineSwitchHost {
    var record = StudioChatRecord(
        "chat",
        "Title",
        Instant.DISTANT_PAST,
        target = source,
        configuration = StudioSessionSettings(source.studioModelId(), "unsupported", ApprovalMode.AutoEdits),
    )
    var active: ActiveSession? = null
    var wasRefreshed = false
    var state = StudioEngineSwitchState()
    var commit: suspend () -> Unit = {}
    var onState: (StudioEngineSwitchState) -> Unit = {}
    override suspend fun <T> withIdleSource(id: String, body: suspend () -> T): T = body()
    override suspend fun switchRecord(id: String): StudioChatRecord = record
    override suspend fun recoverSwitch(id: String) = Unit
    override suspend fun validateSwitch(record: StudioChatRecord, target: EngineTarget): StudioModel =
        StudioModel(target.studioModelId(), "Target", reasoningEfforts = listOf("low"), defaultReasoningEffort = "low")
    override suspend fun switchSource(id: String, target: EngineTarget): ActiveSession = checkNotNull(active)
    override suspend fun refreshSwitchSource(id: String, active: ActiveSession) {
        wasRefreshed = true
    }
    override suspend fun commitSwitch(
        id: String,
        change: StudioEngineSwitch,
        active: ActiveSession?,
        settings: StudioSessionSettings,
    ) {
        commit()
        record = record.copy(target = change.target, configuration = settings)
    }
    override fun switchState(id: String, state: StudioEngineSwitchState, onlyIfIdle: Boolean) {
        if (onlyIfIdle && this.state.isPending) return
        this.state = state
        onState(state)
    }
}

private class SwitchToggles : FeatureToggles {
    var isEnabled = true

    @Suppress("UNCHECKED_CAST") // The only overridden flag is the Boolean engine-switching toggle.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T =
        if (toggle == StudioEngineSwitching) isEnabled as T else toggle.default
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(toggle.default)
}

private fun inspectionFeatures(inspection: TurnInspection): EngineFeatures = object : EngineFeatures {
    private val inspector = object : RestoresSessionTurns {
        override suspend fun checkpoint(request: RequestId): String? = null
        override suspend fun inspect(checkpoint: String?): TurnInspection = inspection
    }

    @Suppress("UNCHECKED_CAST") // Exact feature key in this inspection-only fixture.
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
        if (key == RestoresSessionTurns) FeatureAccess.Available(inspector as F) else FeatureAccess.Unsupported
}
