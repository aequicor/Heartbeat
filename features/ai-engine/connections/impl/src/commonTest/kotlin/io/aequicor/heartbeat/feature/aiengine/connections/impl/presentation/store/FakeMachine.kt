package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.KSerializer
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.collect

/** Records every intent and answers with [result]; a refusing machine is simulated by a non-Accepted result. */
internal class FakeMachine<S : MachineState, I : MachineIntent, O : MachineOutput>(initial: S) : Machine<S, I, O> {
    override val name = "fake"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<O>(extraBufferCapacity = 8)
    val sent = mutableListOf<I>()
    var result = SendResult.Accepted

    override suspend fun send(intent: I): SendResult {
        sent += intent
        return result
    }
}

internal fun TestScope.testStoreFactory(): HeartbeatStoreFactory =
    HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(testScheduler)))

internal fun TestScope.testScopeHandle(): ScopeHandle = TestScopeHandle(backgroundScope)

/** Subscribes like a screen and records the actions it receives into [actions]. */
internal suspend fun <S : MVIState, I : MVIIntent, A : MVIAction> TestScope.subscribe(
    store: Store<S, I, A>,
    actions: MutableList<A> = mutableListOf(),
): Provider<S, I, A> {
    val provider = CompletableDeferred<Provider<S, I, A>>()
    backgroundScope.launch {
        store.collect {
            launch { this@collect.actions.collect { actions += it } }
            provider.complete(this)
            awaitCancellation()
        }
    }
    runCurrent()
    return provider.await()
}

private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}

private class TestScopeHandle(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test/connections"
    override val isClosed = false
    override val savedState = object : ScopeSavedState {
        override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
        override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
        override fun unregister(key: String) = Unit
        override fun snapshot() = SavedBundle(emptyMap())
    }

    override fun onClose(action: () -> Unit) = DisposableHandle { }
}
