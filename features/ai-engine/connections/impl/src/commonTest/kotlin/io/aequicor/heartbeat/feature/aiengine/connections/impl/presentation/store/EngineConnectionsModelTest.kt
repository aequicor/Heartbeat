package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionOperation
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectionsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsOutput
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.impl.KoogId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.engineInfo
import io.aequicor.heartbeat.feature.aiengine.connections.impl.modelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineConnectionsModelTest {
    @Test
    fun `opening and closing check details never sends a machine change`() = runTest {
        machine.state.value = EngineConnectionsState.Active(checkedSettingsSnapshot())
        val model = model()
        val screen = subscribe(model.store)
        val target = assertNotNull(screen.states.value.models?.models?.first()?.check).target
        model.store.intent(EngineConnectionsScreenIntent.ShowCheckDetails(target))
        runCurrent()
        assertNotNull(screen.states.value.checkDetails)
        model.store.intent(EngineConnectionsScreenIntent.DismissCheckDetails)
        runCurrent()
        assertNull(screen.states.value.checkDetails)
        assertEquals(listOf<EngineConnectionsIntent>(EngineConnectionsIntent.Public.Start), machine.sent)
    }

    @Test
    fun `explicit detail retry sends CheckModel and does not save an unrelated draft`() = runTest {
        machine.state.value = EngineConnectionsState.Active(checkedSettingsSnapshot())
        val model = model()
        val screen = subscribe(model.store)
        val target = assertNotNull(screen.states.value.models?.models?.first()?.check).target
        model.store.intent(EngineConnectionsScreenIntent.SetModelEnabled("qwen", true))
        model.store.intent(EngineConnectionsScreenIntent.ShowCheckDetails(target))
        model.store.intent(EngineConnectionsScreenIntent.RetryModelCheck)
        runCurrent()
        val route = requireNotNull(
            (machine.state.value as EngineConnectionsState.Active).snapshot,
        ).selection.defaultTarget
        assertEquals(
            listOf<EngineConnectionsIntent>(
                EngineConnectionsIntent.Public.Apply(ConnectionOperation.CheckModel(requireNotNull(route))),
            ),
            machine.sent.drop(1),
        )
        assertEquals<Set<String>?>(setOf("llama", "qwen"), screen.states.value.modelDraft)
    }

    @Test
    fun `scoped connections cannot switch engines or apply foreign binding changes`() = runTest {
        machine.state.value = EngineConnectionsState.Active(settingsSnapshot())
        val model = EngineConnectionsModel(
            machine,
            testScopeHandle(),
            testStoreFactory(),
            EngineConnectionsTarget(KoogId),
        ) { flowOf(null) }
        val screen = subscribe(model.store)
        model.store.intent(EngineConnectionsScreenIntent.SetModelEnabled("qwen", true))
        runCurrent()
        model.store.intent(EngineConnectionsScreenIntent.SelectEngine("codex"))
        model.store.intent(EngineConnectionsScreenIntent.SelectConnection("foreign"))
        model.store.intent(EngineConnectionsScreenIntent.SetConnectionEnabled("foreign", false))
        model.store.intent(EngineConnectionsScreenIntent.RequestDisconnect("foreign"))
        model.store.intent(EngineConnectionsScreenIntent.ConfirmDisconnect)
        runCurrent()
        assertEquals("koog", screen.states.value.fixedEngine)
        assertEquals("koog", screen.states.value.selectedEngine)
        assertEquals("home", screen.states.value.selectedConnection)
        assertEquals<Set<String>?>(setOf("llama", "qwen"), screen.states.value.modelDraft)
        assertNull(screen.states.value.confirmDisconnect)
        assertTrue(machine.sent.none { it is EngineConnectionsIntent.Public.Apply })
    }

    private val binding = EngineBinding(EngineBindingId("binding-1"), KoogId, AuthSourceId("source-1"))
    private val snapshot = ConnectionsSnapshot(
        listOf(engineInfo(listOf(binding))),
        emptyList(),
        emptyMap(),
        ModelSelection(),
    )
    private val machine = FakeMachine<EngineConnectionsState, EngineConnectionsIntent, EngineConnectionsOutput>(
        EngineConnectionsState.Active(snapshot),
    )

    private fun TestScope.model() =
        EngineConnectionsModel(machine, testScopeHandle(), testStoreFactory()) { flowOf(null) }

    @Test
    fun `the space starts observing and focuses the first engine and connection`() = runTest {
        val screen = subscribe(model().store)
        assertEquals(EngineConnectionsIntent.Public.Start, machine.sent.first())
        assertEquals(KoogId.value, screen.states.value.selectedEngine)
        assertEquals(binding.id.value, screen.states.value.selectedConnection)
    }

    @Test
    fun `model checkboxes edit a draft and save sends one rebased replacement`() = runTest {
        machine.state.value = EngineConnectionsState.Active(settingsSnapshot())
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(EngineConnectionsScreenIntent.SetModelEnabled("qwen", true))
        runCurrent()
        assertTrue(machine.sent.none { it is EngineConnectionsIntent.Public.Apply })
        assertEquals<Set<String>?>(setOf("llama", "qwen"), screen.states.value.modelDraft)
        model.store.intent(EngineConnectionsScreenIntent.SaveModels)
        runCurrent()
        val apply = machine.sent.filterIsInstance<EngineConnectionsIntent.Public.Apply>().single()
        assertEquals(
            ConnectionOperation.SetModelsEnabled(
                EngineBindingId("home"),
                setOf(ModelId("llama"), ModelId("qwen")),
                previous = setOf(ModelId("llama")),
            ),
            apply.operation,
        )
    }

    @Test
    fun `editing one connection never saves its draft to another`() = runTest {
        machine.state.value = EngineConnectionsState.Active(settingsSnapshot())
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(EngineConnectionsScreenIntent.SetModelEnabled("qwen", true))
        runCurrent()
        assertEquals("home", screen.states.value.modelDraftBinding)

        model.store.intent(EngineConnectionsScreenIntent.SelectConnection("work"))
        runCurrent()
        assertEquals(null, screen.states.value.modelDraft)
        assertEquals(null, screen.states.value.modelDraftBaseline)
        assertEquals(false, screen.states.value.isModelSavePending)

        model.store.intent(EngineConnectionsScreenIntent.SaveModels)
        runCurrent()
        assertTrue(machine.sent.none { it is EngineConnectionsIntent.Public.Apply })
    }

    @Test
    fun `delayed save after the draft is observed as saved sends no replacement`() = runTest {
        val initial = settingsSnapshot()
        machine.state.value = EngineConnectionsState.Active(initial)
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(EngineConnectionsScreenIntent.SetModelEnabled("qwen", true))
        runCurrent()
        model.store.intent(EngineConnectionsScreenIntent.SaveModels)
        runCurrent()
        val sent = machine.sent.filterIsInstance<EngineConnectionsIntent.Public.Apply>().single()
        val target = modelInfo(EngineBindingId("home"), "qwen").target
        machine.state.value = EngineConnectionsState.Active(
            initial.copy(selection = initial.selection.withModel(target, true)),
        )
        runCurrent()
        assertEquals(null, screen.states.value.modelDraft)

        model.store.intent(EngineConnectionsScreenIntent.SaveModels)
        runCurrent()

        assertEquals(listOf(sent), machine.sent.filterIsInstance<EngineConnectionsIntent.Public.Apply>())
    }

    @Test
    fun `a confirmed disconnect is sent once and closes the confirmation`() = runTest {
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(EngineConnectionsScreenIntent.RequestDisconnect(binding.id.value))
        model.store.intent(EngineConnectionsScreenIntent.ConfirmDisconnect)
        model.store.intent(EngineConnectionsScreenIntent.ConfirmDisconnect)
        runCurrent()
        val applies = machine.sent.filterIsInstance<EngineConnectionsIntent.Public.Apply>()
        assertEquals(listOf(EngineConnectionsIntent.Public.Apply(ConnectionOperation.Disconnect(binding.id))), applies)
        assertEquals(null, screen.states.value.confirmDisconnect)
    }

    @Test
    fun `a disconnect the machine refuses stays to be confirmed`() = runTest {
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(EngineConnectionsScreenIntent.RequestDisconnect(binding.id.value))
        runCurrent()
        machine.result = SendResult.Ignored
        model.store.intent(EngineConnectionsScreenIntent.ConfirmDisconnect)
        runCurrent()
        assertEquals(binding.id.value, screen.states.value.confirmDisconnect)
    }

    @Test
    fun `selecting an engine drops the connection focus and the confirmation`() = runTest {
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(EngineConnectionsScreenIntent.RequestDisconnect(binding.id.value))
        model.store.intent(EngineConnectionsScreenIntent.SelectEngine(KoogId.value))
        runCurrent()
        assertEquals(null, screen.states.value.confirmDisconnect)
        assertEquals(binding.id.value, screen.states.value.selectedConnection)
    }

    @Test
    fun `retry and dismiss are forwarded to the machine`() = runTest {
        val model = model()
        subscribe(model.store)
        model.store.intent(EngineConnectionsScreenIntent.RetryLoad)
        model.store.intent(EngineConnectionsScreenIntent.RetryFailed)
        model.store.intent(EngineConnectionsScreenIntent.DismissError)
        runCurrent()
        assertEquals(
            listOf(
                EngineConnectionsIntent.Public.RetryLoad,
                EngineConnectionsIntent.Public.RetryFailed,
                EngineConnectionsIntent.Public.DismissError,
            ),
            machine.sent.drop(1),
        )
    }

    @Test
    fun `a failed observation offers no stale entries to change`() = runTest {
        val model = model()
        val screen = subscribe(model.store)
        machine.state.value = EngineConnectionsState.Active(loadFailure = EngineFailure.Unknown())
        runCurrent()
        val state = screen.states.value
        assertTrue(state.engines.isEmpty() && state.connections.isEmpty())
        assertEquals(false, state.isLoading)
        assertEquals(FailureUi.Unknown, state.loadFailure)
    }
}
