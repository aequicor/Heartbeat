package io.aequicor.heartbeat.feature.checklist.impl.presentation.store

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.checklist.api.Checklist
import io.aequicor.heartbeat.feature.checklist.api.ChecklistField
import io.aequicor.heartbeat.feature.checklist.api.ChecklistInput
import io.aequicor.heartbeat.feature.checklist.api.ChecklistIntent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistJournal
import io.aequicor.heartbeat.feature.checklist.api.ChecklistMachineSpec
import io.aequicor.heartbeat.feature.checklist.api.ChecklistOutput
import io.aequicor.heartbeat.feature.checklist.api.ChecklistRoute
import io.aequicor.heartbeat.feature.checklist.api.ChecklistState
import io.aequicor.heartbeat.feature.checklist.impl.domain.ChecklistAccess
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistPhaseUi
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChecklistModelTest {
    @Test
    fun `dispatch waits for reduction and returns the current machine projection without a collector`() = runTest {
        val card = Checklist(
            "card", SessionRef(EngineId("test"), SessionSourceId("profile"), "s"), RequestId("r"), TurnId("t"),
            null, null, null, "Check", listOf(ChecklistField("text", "Notes", ChecklistInput.Text)),
        )
        val machine = FakeChecklistMachine(ChecklistState.Ready(ChecklistJournal(listOf(card))))
        val model = ChecklistModel(
            machine,
            ChecklistRoute("card"),
            object : ChecklistAccess {
                override suspend fun isEnabled() = true
            },
            TestScopeHandle(backgroundScope),
            HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(testScheduler))),
        )
        val pending = async { model.dispatch(ChecklistScreenIntent.Text("text", "Tested")) }
        runCurrent()
        assertFalse(pending.isCompleted)
        machine.gate.complete(Unit)
        val draft = pending.await()
        assertEquals("Tested", draft.fields.single().text)
        assertTrue(draft.isSaving)
        assertTrue(draft.revision > 0)
        val completed = model.dispatch(ChecklistScreenIntent.Complete)
        assertEquals(ChecklistPhaseUi.Completed, completed.phase)
        assertTrue(completed.revision > draft.revision)
        assertEquals("Tested", completed.fields.single().text)
    }
}

private class FakeChecklistMachine(initial: ChecklistState) :
    Machine<ChecklistState, ChecklistIntent, ChecklistOutput> {
    override val name = "checklist"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<ChecklistOutput>()
    val gate = CompletableDeferred<Unit>()

    override suspend fun send(intent: ChecklistIntent): SendResult {
        gate.await()
        val resolved = ChecklistMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolved.to
        return SendResult.Accepted
    }
}

private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}

private class TestScopeHandle(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test/questionnaire"
    override val isClosed = false
    override val savedState = object : ScopeSavedState {
        override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
        override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
        override fun unregister(key: String) = Unit
        override fun snapshot() = SavedBundle(emptyMap())
    }

    override fun onClose(action: () -> Unit) = DisposableHandle { }
}
