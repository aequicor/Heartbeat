package io.aequicor.heartbeat.feature.serviceasui.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiWrite
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
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.collect

/** Stores of a test running on the scheduler of [scope]. */
internal fun testStores(scope: TestScope): HeartbeatStoreFactory {
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    return HeartbeatStoreFactory(
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
        },
    )
}

/** Subscribes to [store] for the rest of the test, as a screen does. */
internal suspend fun <S : MVIState, I : MVIIntent, A : MVIAction> TestScope.subscribe(
    store: Store<S, I, A>,
    actions: MutableList<A>? = null,
): Provider<S, I, A> {
    val provider = CompletableDeferred<Provider<S, I, A>>()
    backgroundScope.launch {
        store.collect {
            provider.complete(this)
            actions?.let { list -> launch { this@collect.actions.collect { list += it } } }
            awaitCancellation()
        }
    }
    runCurrent()
    return provider.await()
}

/** A screen scope living as long as the test's background work. */
internal class TestScopeHandle(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "test-screen"
    override val savedState: ScopeSavedState get() = error("unused")
    override val isClosed = false
    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
}

/** The profile machine of a test: records intents and answers library writes with [answer]. */
internal class FakeServiceUiMachine(
    initial: ServiceUiState,
    var answer: (UiWrite) -> ServiceUiOutput? = { ServiceUiOutput.EditApplied(it.id, null) },
) : Machine<ServiceUiState, ServiceUiIntent, ServiceUiOutput> {
    override val name = "service_ui"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<ServiceUiOutput>(extraBufferCapacity = 16)
    val sent = mutableListOf<ServiceUiIntent>()

    override suspend fun send(intent: ServiceUiIntent): SendResult {
        sent += intent
        if (intent is ServiceUiIntent.Internal.Edit) answer(intent.write)?.let { outputs.emit(it) }
        return SendResult.Accepted
    }
}
