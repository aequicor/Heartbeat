package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationUpdate
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudioConfigurationObservationTest {
    @Test
    fun `closing a native handle releases configuration and correction collectors`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        val session = fixture.access.sessions.getValue("chat")
        fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        runCurrent()
        assertEquals(1, native.configuration.subscriptionCount.value)
        assertEquals(1, native.updates.subscriptionCount.value)
        assertEquals(1, session.state.subscriptionCount.value)

        session.close()
        runCurrent()
        assertEquals(0, native.configuration.subscriptionCount.value)
        assertEquals(0, native.updates.subscriptionCount.value)
        assertEquals(0, session.state.subscriptionCount.value)

        val saved = fixture.access.saved.toList()
        native.configuration.value = fixture.initial
        native.updates.emit(
            SessionConfigurationUpdate(native.calls.single().first, fixture.initial, unsupported),
        )
        runCurrent()
        assertEquals(saved, fixture.access.saved)
        assertEquals("high", fixture.access.states.getValue("chat").applied.reasoningEffort)
        assertIs<FeedbackOutcome.Applied>(fixture.registry.publications.last().outcome)
    }

    @Test
    fun `replacing a handle cancels its collectors and preserves the replacement observation`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val previous = fixture.add()
        val oldSession = fixture.access.sessions.getValue("chat")
        fixture.controller.observe(fixture.access, "chat", oldSession, fixture.target)
        runCurrent()
        assertEquals(1, previous.configuration.subscriptionCount.value)
        assertEquals(1, previous.updates.subscriptionCount.value)

        val current = fixture.add()
        val newSession = fixture.access.sessions.getValue("chat")
        fixture.controller.observe(fixture.access, "chat", newSession, fixture.target)
        runCurrent()
        assertEquals(0, previous.configuration.subscriptionCount.value)
        assertEquals(0, previous.updates.subscriptionCount.value)
        assertEquals(0, oldSession.state.subscriptionCount.value)
        assertEquals(1, current.configuration.subscriptionCount.value)
        assertEquals(1, current.updates.subscriptionCount.value)

        oldSession.close()
        runCurrent()
        fixture.controller.observe(fixture.access, "chat", newSession, fixture.target)
        runCurrent()
        assertEquals(1, current.configuration.subscriptionCount.value)
        assertEquals(1, current.updates.subscriptionCount.value)
        current.configuration.value = fixture.initial.copy(reasoningEffort = "high")
        runCurrent()
        assertEquals("high", fixture.access.records.getValue("chat").configuration?.reasoningEffort)
    }

    @Test
    fun `a closed handle cannot be observed again`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        val session = fixture.access.sessions.getValue("chat")
        fixture.controller.observe(fixture.access, "chat", session, fixture.target)
        runCurrent()
        session.close()
        runCurrent()

        fixture.controller.observe(fixture.access, "chat", session, fixture.target)
        runCurrent()
        assertEquals(0, native.configuration.subscriptionCount.value)
        assertEquals(0, native.updates.subscriptionCount.value)
        assertEquals(0, session.state.subscriptionCount.value)
    }

    @Test
    fun `detaching the screen waiter keeps later native corrections observable`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        native.onApply = { _, _ ->
            started.complete(Unit)
            release.await()
            fixture.initial.copy(reasoningEffort = "high")
        }
        val screen = launch { fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high")) }
        started.await()
        screen.cancelAndJoin()
        release.complete(Unit)
        runCurrent()
        assertEquals(1, native.configuration.subscriptionCount.value)
        assertEquals(1, native.updates.subscriptionCount.value)

        native.configuration.value = fixture.initial
        native.updates.emit(
            SessionConfigurationUpdate(native.calls.single().first, fixture.initial, unsupported),
        )
        runCurrent()
        assertEquals("low", fixture.access.records.getValue("chat").configuration?.reasoningEffort)
        assertIs<FeedbackOutcome.Failed>(fixture.registry.publications.last().outcome)
    }

    private val unsupported = EngineFailure.Engine(EngineFailureReason.UnsupportedCapability)
}
