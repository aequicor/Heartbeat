package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardOutput
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState
import io.aequicor.heartbeat.feature.aiengine.connections.api.CredentialInput
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ApiKeyMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.KoogId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.engineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectWizardModelTest {
    private val machine =
        FakeMachine<ConnectWizardState, ConnectWizardIntent, ConnectWizardOutput>(ConnectWizardState.Idle)

    private fun TestScope.model() =
        ConnectWizardModel(machine, ConnectEngineRoute(KoogId), testScopeHandle(), testStoreFactory())

    @Test
    fun `the wizard starts for the routed engine and closes on a result reached while unsubscribed`() = runTest {
        val model = model()
        runCurrent()
        assertEquals(ConnectWizardIntent.Public.Start(KoogId), machine.sent.first())
        machine.state.value = ConnectWizardState.Finished(EngineBindingId("binding-1"))
        runCurrent()
        val actions = mutableListOf<ConnectWizardScreenAction>()
        subscribe(model.store, actions)
        runCurrent()
        assertEquals(listOf<ConnectWizardScreenAction>(ConnectWizardScreenAction.Close("binding-1")), actions)
    }

    @Test
    fun `a cancelled wizard closes without a binding`() = runTest {
        val model = model()
        val actions = mutableListOf<ConnectWizardScreenAction>()
        subscribe(model.store, actions)
        machine.state.value = ConnectWizardState.Cancelled
        runCurrent()
        assertEquals(listOf<ConnectWizardScreenAction>(ConnectWizardScreenAction.Close(null)), actions)
    }

    @Test
    fun `an accepted connect hands the key over and clears it from the screen`() = runTest {
        machine.state.value = ConnectWizardState.ChoosingMethod(engineInfo())
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(ConnectWizardScreenIntent.EditKey("sk-1"))
        model.store.intent(ConnectWizardScreenIntent.Connect)
        runCurrent()
        val connect = assertIs<ConnectWizardIntent.Public.Connect>(machine.sent.last())
        assertEquals(ApiKeyMethod.id, connect.method)
        val key = assertIs<CredentialInput.ApiKey>(connect.credential).key
        assertEquals("sk-1", key.reveal { it.concatToString() })
        assertEquals("", screen.states.value.form.key.value)
    }

    @Test
    fun `a rejected connect closes the key and keeps the form`() = runTest {
        machine.state.value = ConnectWizardState.ChoosingMethod(engineInfo())
        val model = model()
        val screen = subscribe(model.store)
        machine.result = SendResult.Ignored
        model.store.intent(ConnectWizardScreenIntent.EditKey("sk-1"))
        model.store.intent(ConnectWizardScreenIntent.Connect)
        runCurrent()
        val connect = assertIs<ConnectWizardIntent.Public.Connect>(machine.sent.last())
        val key = assertIs<CredentialInput.ApiKey>(connect.credential).key
        assertFailsWith<IllegalStateException> { key.reveal { } }
        assertEquals("sk-1", screen.states.value.form.key.value)
    }

    @Test
    fun `an incomplete form is reported without asking the machine`() = runTest {
        machine.state.value = ConnectWizardState.ChoosingMethod(engineInfo())
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(ConnectWizardScreenIntent.Connect)
        runCurrent()
        assertEquals(FormError.MissingKey, screen.states.value.formError)
        assertTrue(machine.sent.none { it is ConnectWizardIntent.Public.Connect })
    }

    @Test
    fun `system back is left to the machine`() = runTest {
        val model = model()
        subscribe(model.store)
        model.store.intent(ConnectWizardScreenIntent.SystemBack)
        runCurrent()
        assertEquals(ConnectWizardIntent.Public.Dismiss, machine.sent.last())
    }

    @Test
    fun `returning to the engine step drops a typed key`() = runTest {
        machine.state.value = ConnectWizardState.ChoosingMethod(engineInfo())
        val model = model()
        val screen = subscribe(model.store)
        model.store.intent(ConnectWizardScreenIntent.EditKey("sk-1"))
        runCurrent()
        machine.state.value = ConnectWizardState.ChoosingEngine(listOf(engineInfo()))
        runCurrent()
        assertEquals(CredentialForm(), screen.states.value.form)
        assertEquals(null, screen.states.value.selectedMethod)
    }

    @Test
    fun `the key intent never prints the key`() {
        assertEquals("EditKey(***)", ConnectWizardScreenIntent.EditKey("sk-1").toString())
    }
}
