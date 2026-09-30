package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationUpdate
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import io.aequicor.heartbeat.feature.feedback.api.FeedbackAnchor
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudioConfigurationControllerTest {
    @Test
    fun `feedback uses the stored transcript anchor after the legacy carrier was cleared`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        fixture.add()
        val record = fixture.access.records.getValue("chat")
        assertTrue(record.items.isEmpty())
        val anchor = FeedbackAnchor(record.ref, ItemId("database-tail"))
        fixture.access.anchors["chat"] = anchor

        fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        runCurrent()

        assertTrue(fixture.registry.publications.isNotEmpty())
        assertTrue(fixture.registry.publications.all { it.anchor == anchor })
    }

    @Test
    fun `screen cancellation leaves the accepted profile operation running`() = runTest {
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
        assertIs<FeedbackOutcome.Pending>(fixture.registry.publications.single().outcome)
        screen.cancelAndJoin()
        release.complete(Unit)
        runCurrent()
        assertEquals("high", fixture.access.records.getValue("chat").configuration?.reasoningEffort)
        assertIs<FeedbackOutcome.Applied>(fixture.registry.publications.last().outcome)
        assertEquals(null, fixture.access.states.getValue("chat").pendingOperation)
    }

    @Test
    fun `changing one conversation keeps another conversation configuration isolated`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val first = fixture.add("first")
        val second = fixture.add("second")
        fixture.controller.configure(fixture.access, "first", StudioSettingChange.Effort("high"))
        runCurrent()
        assertEquals(1, first.calls.size)
        assertTrue(second.calls.isEmpty())
        assertEquals("high", fixture.access.records.getValue("first").configuration?.reasoningEffort)
        assertEquals("low", fixture.access.records.getValue("second").configuration?.reasoningEffort)
        assertTrue(fixture.registry.publications.all { it.source == "first" })
    }

    @Test
    fun `native clamping persists and reports the acknowledged value`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        native.onApply = { _, _ -> fixture.initial }
        fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        runCurrent()
        val applied = assertIs<FeedbackOutcome.Applied>(fixture.registry.publications.last().outcome)
        assertEquals("low", applied.configuration.reasoningEffort)
        assertEquals("low", fixture.access.records.getValue("chat").configuration?.reasoningEffort)
        assertTrue(fixture.registry.intents.filterIsInstance<EffortConfigurationIntent.Public.Select>().isEmpty())
    }

    @Test
    fun `native failure restores the previous selection and produces error feedback`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        val failure = EngineFailure.Transport(TransportFailureReason.Timeout)
        native.onApply = { _, _ -> throw EngineException(failure) }
        fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        runCurrent()
        assertEquals("low", fixture.access.states.getValue("chat").applied.reasoningEffort)
        assertTrue(fixture.access.saved.all { it.second.reasoningEffort == "low" })
        val failed = assertIs<FeedbackOutcome.Failed>(fixture.registry.publications.last().outcome)
        assertEquals(failure, failed.failure)
        assertEquals(fixture.registry.publications.first().id, fixture.registry.publications.last().id)
    }

    @Test
    fun `choosing the already applied value creates no call or feedback card`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("low"))
        runCurrent()
        assertTrue(native.calls.isEmpty())
        assertTrue(fixture.registry.publications.isEmpty())
        assertTrue(fixture.access.attached.isEmpty())
        assertTrue(fixture.access.saved.isEmpty())
    }

    @Test
    fun `a model route from another engine is rejected before runtime access`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        val requested = fixture.target.copy(engine = EngineId("another-engine"))
        fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Model(requested.studioModelId()))
        runCurrent()
        assertTrue(native.calls.isEmpty())
        assertTrue(fixture.access.validated.isEmpty())
        assertTrue(fixture.access.attached.isEmpty())
        assertEquals(
            EngineFailure.Access(AccessFailureReason.OperationNotAllowed),
            assertIs<FeedbackOutcome.Failed>(fixture.registry.publications.last().outcome).failure,
        )
        assertEquals(fixture.initial.studio(fixture.target), fixture.access.states.getValue("chat").applied)
    }

    @Test
    fun `an unsupported running runtime fails explicitly without deferring the change`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        val session = fixture.access.sessions.getValue("chat")
        session.native = null
        session.state.value = ActiveSessionState.Running(Turn(TurnId("turn"), RequestId("request"), fixture.target))
        fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        runCurrent()
        assertEquals(
            EngineFailure.Engine(EngineFailureReason.UnsupportedCapability),
            assertIs<FeedbackOutcome.Failed>(fixture.registry.publications.last().outcome).failure,
        )
        session.state.value = ActiveSessionState.Ready()
        runCurrent()
        assertTrue(native.calls.isEmpty())
        assertTrue(fixture.access.saved.isEmpty())
        assertEquals("low", fixture.access.states.getValue("chat").applied.reasoningEffort)
        assertEquals(2, fixture.registry.publications.size)
    }

    @Test
    fun `later provider correction updates the same feedback card and actual selection`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        runCurrent()
        val operation = native.calls.single().first
        val corrected = fixture.initial.copy(reasoningEffort = null)
        native.configuration.value = corrected
        val failure = EngineFailure.Engine(EngineFailureReason.UnsupportedCapability)
        native.updates.emit(SessionConfigurationUpdate(operation, corrected, failure))
        runCurrent()
        val records = fixture.registry.publications
        assertEquals(3, records.size)
        assertTrue(records.all { it.id == operation })
        assertEquals(listOf(0L, 1L, 2L), records.map { it.revision })
        assertEquals(failure, assertIs<FeedbackOutcome.Failed>(records.last().outcome).failure)
        assertEquals(null, fixture.access.records.getValue("chat").configuration?.reasoningEffort)
        assertEquals(null, fixture.access.states.getValue("chat").applied.reasoningEffort)
        assertFalse(fixture.access.states.getValue("chat").pendingOperation != null)
    }

    @Test
    fun `provider correction during preference persistence is observed and survives the final save`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        val native = fixture.add()
        val saving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.access.beforeSave = {
            saving.complete(Unit)
            release.await()
        }
        val operation = launch {
            fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        }
        saving.await()
        runCurrent()
        assertEquals(native.calls.single().first, fixture.access.states.getValue("chat").pendingOperation)
        val corrected = fixture.initial.copy(reasoningEffort = null)
        native.configuration.value = corrected
        val id = native.calls.single().first
        val failure = EngineFailure.Engine(EngineFailureReason.UnsupportedCapability)
        native.updates.emit(SessionConfigurationUpdate(id, corrected, failure))
        runCurrent()
        fixture.access.beforeSave = {}
        release.complete(Unit)
        operation.join()
        runCurrent()
        assertEquals(null, fixture.access.records.getValue("chat").configuration?.reasoningEffort)
        assertEquals(failure, assertIs<FeedbackOutcome.Failed>(fixture.registry.publications.last().outcome).failure)
        assertTrue(fixture.registry.intents.filterIsInstance<EffortConfigurationIntent.Public.Select>().isEmpty())
    }

    @Test
    fun `disabling feedback still installs the configuration without adding cards`() = runTest {
        val fixture = StudioConfigurationFixture(this)
        fixture.isFeedbackEnabled = false
        val native = fixture.add()
        fixture.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        runCurrent()
        assertEquals(1, native.calls.size)
        assertEquals("high", fixture.access.records.getValue("chat").configuration?.reasoningEffort)
        assertTrue(fixture.registry.publications.isEmpty())
    }
}
