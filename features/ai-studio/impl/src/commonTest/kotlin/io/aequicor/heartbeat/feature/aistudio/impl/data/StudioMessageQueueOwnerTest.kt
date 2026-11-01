package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatQueue
import io.aequicor.heartbeat.feature.aistudio.api.StudioMessageQueues
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudioMessageQueueOwnerTest {
    private val message = StudioQueuedMessage(
        "captured",
        "Keep this input once",
        emptyList(),
        RunSettings("model", ReasoningEffort.High, ApprovalMode.Ask),
    )

    @Test
    fun `failed resume after capture acknowledges the durable message and keeps delivery paused`() = runTest {
        val fixture = fixture(StudioMessageQueues(mapOf("chat" to StudioChatQueue(isPaused = true))))

        fixture.queue.enqueue("chat", message, null)
        runCurrent()

        assertCapturedOnce(fixture)
        assertTrue(fixture.stores.snapshot().chat("chat").isPaused)
    }

    @Test
    fun `failed fallback pause after refused correction still acknowledges the captured input`() = runTest {
        val fixture = fixture(StudioMessageQueues())

        fixture.queue.enqueue("chat", message, null, isCorrection = true)
        runCurrent()

        assertCapturedOnce(fixture)
        // The failed second write left an unpaused disk snapshot; the live barrier still prevents its dispatch.
        assertFalse(fixture.stores.snapshot().chat("chat").isPaused)
    }

    private suspend fun assertCapturedOnce(fixture: QueueOwnerFixture) {
        assertEquals(2, fixture.stores.attempts)
        assertEquals(listOf(message), fixture.stores.snapshot().chat("chat").messages)
        assertEquals(listOf(message), fixture.queue.state.value.chat("chat").messages)
        assertTrue(fixture.queue.state.value.chat("chat").isPaused)
        assertEquals(emptyList(), fixture.host.delivered)
        assertEquals(false, fixture.queue.notices.value["chat"]?.isSuccess)
    }

    private suspend fun TestScope.fixture(initial: StudioMessageQueues): QueueOwnerFixture {
        val stores = QueueFailureStores()
        stores.seed(initial)
        return QueueOwnerFixture(this, stores)
    }
}

private class QueueOwnerFixture(test: TestScope, val stores: QueueFailureStores) {
    val host = QueueOwnerHost()
    private val profile = RunProfile(test.backgroundScope)
    val queue = StudioMessageQueueOwner(
        stores,
        profile,
        QueueOwnerLauncher,
        lazyOf(host),
        StudioRunCoordinator(profile, RunClock),
        ChecklistTestToggles(),
    )
}

/** Fails the requested follow-up write after the initial message has reached durable storage. */
private class QueueFailureStores(private val delegate: ChecklistTestStores = ChecklistTestStores()) :
    DataStores by delegate {
    var attempts = 0
        private set

    suspend fun seed(data: StudioMessageQueues) = delegate.keyValue(QueueOwnerSpec).set(QueueOwnerKey, data)

    suspend fun snapshot(): StudioMessageQueues = checkNotNull(delegate.keyValue(QueueOwnerSpec).get(QueueOwnerKey))

    override fun keyValue(spec: KeyValueSpec): KeyValueStore {
        val store = delegate.keyValue(spec)
        return object : KeyValueStore by store {
            override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
                attempts++
                check(attempts != 2) { "Disk write refused" }
                store.set(key, value, retention)
            }
        }
    }
}

private class QueueOwnerHost : StudioQueueHost {
    val delivered = mutableListOf<StudioQueuedMessage>()

    override suspend fun queuedRun(
        chat: String,
        message: StudioQueuedMessage,
        environment: StudioChatEnvironment?,
        submission: StudioSubmissionGate,
        accepted: suspend () -> Unit,
    ) {
        delivered += message
        accepted()
    }

    override suspend fun steeringTarget(chat: String): StudioSteeringTarget = error("No steerable native turn")

    override suspend fun steerQueued(target: StudioSteeringTarget, message: StudioQueuedMessage) =
        error("Unexpected correction delivery")
}

/** Runs the real pure spec and persistence feedback without adding an impl-to-impl runtime dependency. */
private object QueueOwnerLauncher : MachineLauncher {
    override fun <S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput> launch(
        spec: MachineSpec<S, I, E, O>,
        scope: ScopeHandle,
        effects: EffectHandler<E, I>,
        isAddressable: Boolean,
    ): Machine<S, I, O> = QueueOwnerMachine(spec, scope, effects)
}

private class QueueOwnerMachine<S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput>(
    private val spec: MachineSpec<S, I, E, O>,
    private val scope: ScopeHandle,
    private val effects: EffectHandler<E, I>,
) : Machine<S, I, O>,
    EffectScope<I> {
    private val log = Log.tag("QueueOwnerTestMachine")
    override val name = spec.name
    override val state = MutableStateFlow(spec.initial)
    override val outputs = MutableSharedFlow<O>(extraBufferCapacity = 16)

    override suspend fun send(intent: I): SendResult {
        val resolution = spec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolution.to
        resolution.outputs.forEach { outputs.emit(it) }
        resolution.effects.forEach { effect ->
            scope.coroutineScope.launch {
                try {
                    effects.handle(effect, this@QueueOwnerMachine)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.w(error) { "Test effect failed" }
                    send(spec.onEffectFailure(effect, error) ?: throw error)
                }
            }
        }
        return SendResult.Accepted
    }
}

private val QueueOwnerSpec = KeyValueSpec("studio_message_queue", readPolicy = KeyValueReadPolicy.Strict)
private val QueueOwnerKey = jsonKey("queues", StudioMessageQueues.serializer())
