package io.aequicor.heartbeat.feature.harness.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.LibraryOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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

/** Records commands; the test sets the committed state, so screens are checked against exact machine input. */
internal class RecordingLibrary(initial: HarnessState) :
    HarnessMachine,
    HarnessLibraryClient {
    override val name: String = "harness"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<HarnessOutput>(extraBufferCapacity = 8)
    val sent = mutableListOf<HarnessIntent>()
    var outcome: LibraryOutcome = LibraryOutcome.Committed(HarnessOutput.Updated(request(), HarnessId("any")))

    override suspend fun send(intent: HarnessIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }

    override val current: HarnessState.Ready? get() = state.value as? HarnessState.Ready
    override suspend fun ready(): HarnessState.Ready? = current

    override suspend fun submit(intent: HarnessIntent.Public): LibraryOutcome {
        sent += intent
        return outcome
    }

    override suspend fun sourceProject(workspace: WorkspaceRef?): WorkspaceRef? = workspace
}

internal fun TestScope.storeFactory() = HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(testScheduler)))

/** Subscribes to a started store and returns its provider once the first state is delivered. */
internal suspend fun <S : MVIState, I : MVIIntent, A : MVIAction> TestScope.subscribe(
    store: Store<S, I, A>,
): Provider<S, I, A> {
    val provider = CompletableDeferred<Provider<S, I, A>>()
    backgroundScope.launch {
        store.collect {
            provider.complete(this)
            awaitCancellation()
        }
    }
    runCurrent()
    return provider.await()
}

private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
}
