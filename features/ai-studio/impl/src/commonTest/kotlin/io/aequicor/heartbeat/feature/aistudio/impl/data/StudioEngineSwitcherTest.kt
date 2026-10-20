package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
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
    var state = StudioEngineSwitchState()
    var commit: suspend () -> Unit = {}
    var onState: (StudioEngineSwitchState) -> Unit = {}
    override suspend fun <T> withIdleSource(id: String, body: suspend () -> T): T = body()
    override suspend fun switchRecord(id: String): StudioChatRecord = record
    override suspend fun recoverSwitch(id: String) = Unit
    override suspend fun validateSwitch(record: StudioChatRecord, target: EngineTarget): StudioModel =
        StudioModel(target.studioModelId(), "Target", reasoningEfforts = listOf("low"), defaultReasoningEffort = "low")
    override suspend fun switchSource(id: String, target: EngineTarget): ActiveSession = error("No source exists")
    override suspend fun refreshSwitchSource(id: String, active: ActiveSession) = error("No native request allowed")
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
