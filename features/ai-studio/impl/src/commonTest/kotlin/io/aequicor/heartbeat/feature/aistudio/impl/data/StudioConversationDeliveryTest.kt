package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationMessage
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudioConversationDeliveryTest {
    @Test
    fun `delivery acknowledges before execution and preserves current model and unknown outcome`() = runTest {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        val runtime = DeliveryRuntime()
        val coordinator = StudioRunCoordinator(RunProfile(this), RunClock)
        val host = RunHost(mutableListOf())
        val turn = TurnId("native-turn")
        val finished = CompletableDeferred<Unit>()
        val native = SessionRef(EngineId("test"), SessionSourceId("local"), "child-native")
        val executed = mutableListOf<StudioTurnRequest>()
        host.execute = { request ->
            executed += request
            val gate = request.submission as StudioHelperSubmission
            gate.withPreparation(native, null) {
                gate.begin()
                gate.send { attempts.accepted(HelperId(record.id), request.request, native, turn) }
                finished.await()
                RunOutcome.Completed
            }
        }
        val delivery = StudioConversationDelivery(
            attempts,
            emptyHelperAdmissions(),
            lazyOf(host),
            lazyOf(runtime),
            coordinator,
            RunProfile(this),
        )
        val child = LogicalSessionId(record.id)
        val message = HostConversationMessage(RequestId("message"), "Generated creator text")
        val cause = RequestInitiator(native.copy(nativeId = "parent"), RequestId("parent-request"))
        delivery.enqueue(child, message, cause) { record }
        assertTrue(delivery.isPending(child, message.request))
        assertTrue(executed.isEmpty())
        assertEquals(StudioHelperPhase.Preparing, attempts.receipt(HelperId(record.id), message.request)?.phase)
        val changed = checkNotNull(record.target).copy(model = ModelId("user-selected"))
        runtime.state.value = StudioRuntimeState(
            configurations = mapOf(
                record.id to StudioSessionConfiguration(StudioSessionSettings(changed.studioModelId())),
            ),
        )
        runCurrent()
        val sent = executed.single()
        assertEquals(changed.studioModelId(), sent.settings.modelId)
        assertFalse(sent.isUserPrompt)
        assertEquals(null, sent.userInstruction)
        assertEquals(setOf(cause), sent.causes)
        assertTrue(sent.prompt.startsWith("[Creator agent]"))
        finished.complete(Unit)
        runCurrent()
        assertFalse(delivery.isPending(child, message.request))
        // A local host return is not native terminal proof, even when it says Completed.
        assertEquals(StudioHelperPhase.Accepted, attempts.receipt(HelperId(record.id), message.request)?.phase)
        delivery.enqueue(child, message, cause) { record }
        runCurrent()
        assertEquals(1, executed.size)
    }

    @Test
    fun `cancelling the caller only detaches from an accepted profile operation`() = runTest {
        val released = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var isFinished = false
        val delivery = StudioConversationDelivery(
            StudioHelperAttempts(ChecklistTestStores()),
            emptyHelperAdmissions(),
            lazy { error("Unused host") },
            lazy { error("Unused runtime") },
            StudioRunCoordinator(RunProfile(this), RunClock),
            RunProfile(this),
        )
        val caller = async {
            delivery.owned {
                entered.complete(Unit)
                released.await()
                isFinished = true
            }
        }
        entered.await()
        caller.cancelAndJoin()
        assertFalse(isFinished)
        released.complete(Unit)
        runCurrent()
        assertTrue(isFinished)
    }
}

private val record = StudioChatRecord(
    "child",
    "Child",
    RunClock.now(),
    target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("initial")),
    identity = StudioSessionIdentity(
        StudioIdentityOrigin.AgentCreated,
        LogicalSessionContext(LogicalSessionId("root"), SessionKind.Session).subsession(LogicalSessionId("child")),
    ),
)

private class DeliveryRuntime : StudioRuntime {
    override val state = MutableStateFlow(StudioRuntimeState())
    override suspend fun defaults(): RunSettings = DefaultRunSettings
    override suspend fun run(sessionId: String, prompt: String, settings: RunSettings): RunOutcome = error("Unused")
    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun respond(
        sessionId: String,
        requestId: String,
        optionId: String,
        answer: StudioPermissionAnswer?,
    ) = Unit
}
