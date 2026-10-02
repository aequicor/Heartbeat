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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineConnectionsModelTest {
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

    private fun TestScope.model() = EngineConnectionsModel(machine, testScopeHandle(), testStoreFactory())

    @Test
    fun `the space starts observing and focuses the first engine and connection`() = runTest {
        val screen = subscribe(model().store)
        assertEquals(EngineConnectionsIntent.Public.Start, machine.sent.first())
        assertEquals(KoogId.value, screen.states.value.selectedEngine)
        assertEquals(binding.id.value, screen.states.value.selectedConnection)
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
    fun `a saved launch draft is sent once and the saved settings are shown again`() = runTest {
        machine.state.value = EngineConnectionsState.Active(managedSnapshot())
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(EngineConnectionsScreenIntent.SelectEngine(CodexId.value))
        val draft = LaunchDraftUi(executable = "/opt/codex/bin/codex")
        model.store.intent(EngineConnectionsScreenIntent.EditLaunch(draft))
        runCurrent()
        assertEquals(draft, screen.states.value.panel?.launch?.draft)

        model.store.intent(EngineConnectionsScreenIntent.SaveLaunch)
        runCurrent()

        val configure = EngineCommand.Configure(LaunchSettings(executable = "/opt/codex/bin/codex"))
        assertEquals(
            listOf(EngineConnectionsIntent.Public.Apply(ConnectionOperation.ManageEngine(CodexId, configure))),
            machine.sent.filterIsInstance<EngineConnectionsIntent.Public.Apply>(),
        )
        assertEquals(null, screen.states.value.launchDraft)
    }

    @Test
    fun `an uninstall waits for confirmation and a switched engine forgets it`() = runTest {
        machine.state.value = EngineConnectionsState.Active(managedSnapshot())
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(EngineConnectionsScreenIntent.SelectEngine(CodexId.value))
        model.store.intent(EngineConnectionsScreenIntent.RequestEngineAction(EngineActionUi.Uninstall))
        runCurrent()
        assertEquals(EngineActionUi.Uninstall, screen.states.value.confirmAction)
        assertTrue(machine.sent.none { it is EngineConnectionsIntent.Public.Apply })

        model.store.intent(EngineConnectionsScreenIntent.ConfirmEngineAction)
        model.store.intent(EngineConnectionsScreenIntent.RequestEngineAction(EngineActionUi.Logout))
        model.store.intent(EngineConnectionsScreenIntent.SelectEngine(KoogId.value))
        runCurrent()

        val uninstall = EngineCommand.Start(EngineAction.Uninstall)
        assertEquals(
            listOf(EngineConnectionsIntent.Public.Apply(ConnectionOperation.ManageEngine(CodexId, uninstall))),
            machine.sent.filterIsInstance<EngineConnectionsIntent.Public.Apply>(),
        )
        assertEquals(null, screen.states.value.confirmAction)
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
