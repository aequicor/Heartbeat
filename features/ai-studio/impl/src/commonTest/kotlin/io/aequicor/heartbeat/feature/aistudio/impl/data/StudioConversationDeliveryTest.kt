package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationMessage
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatQueue
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedOrigin
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StudioConversationDeliveryTest {
    @Test
    fun `creator input is durable before native admission and retries preserve its original envelope`() = runTest {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        val runtime = DeliveryRuntime()
        val queue = CapturedCreatorQueue()
        val delivery = StudioConversationDelivery(attempts, lazyOf(runtime), queue, RunProfile(this))
        val child = LogicalSessionId(record.id)
        val message = HostConversationMessage(RequestId("message"), "Generated creator text")
        val native = SessionRef(EngineId("test"), SessionSourceId("local"), "parent")
        val cause = RequestInitiator(native, RequestId("parent-request"))
        delivery.enqueue(child, message, cause) { record }
        val saved = queue.saved.messages.single()
        assertTrue(delivery.isPending(child, message.request))
        assertEquals(null, attempts.receipt(HelperId(record.id), message.request))
        assertEquals(null, saved.userInstruction)
        assertTrue(saved.origin is StudioQueuedOrigin.Creator)
        assertEquals(record.target, saved.configuration?.target)
        assertTrue(saved.text.startsWith("[Creator agent]"))
        assertTrue(saved.directives.single().contains("not a direct human instruction"))
        queue.saved = queue.saved.copy(messages = emptyList())
        val changed = checkNotNull(record.target).copy(model = ModelId("future-preference"))
        runtime.state.value = StudioRuntimeState(
            configurations = mapOf(
                record.id to StudioSessionConfiguration(StudioSessionSettings(changed.studioModelId())),
            ),
        )
        val restoredQueue = CapturedCreatorQueue().also {
            it.saved = Json.decodeFromString(Json.encodeToString(queue.saved))
        }
        val restored = StudioConversationDelivery(attempts, lazyOf(runtime), restoredQueue, RunProfile(this))
        restored.enqueue(child, message, cause) { record }
        assertTrue(restoredQueue.saved.messages.isEmpty())
        assertEquals(saved, restoredQueue.saved.creatorMessages[message.request.value])
        assertFailsWith<IllegalStateException> {
            restored.enqueue(child, message.copy(text = "Changed"), cause) { record }
        }
        assertFailsWith<IllegalStateException> {
            restored.enqueue(child, message, cause.copy(request = RequestId("other-source"))) { record }
        }
    }

    @Test
    fun `queue dispatch keeps creator provenance and configuration out of human authority`() = runTest {
        val queue = CapturedCreatorQueue()
        val producer = StudioConversationDelivery(
            StudioHelperAttempts(ChecklistTestStores()),
            lazyOf(DeliveryRuntime()),
            queue,
            RunProfile(this),
        )
        val cause = RequestInitiator(
            SessionRef(EngineId("test"), SessionSourceId("local"), "parent"),
            RequestId("cause"),
        )
        producer.enqueue(
            LogicalSessionId(record.id),
            HostConversationMessage(RequestId("queued"), "Inspect"),
            cause,
        ) { record }
        val saved = queue.saved.messages.single()
        var validated: StudioQueuedOrigin.Creator? = null
        val access = object : StudioQueueAccess {
            override suspend fun validateQueuedInput(chat: String, environment: StudioChatEnvironment?): Unit =
                error("Creator must not use the human admission boundary")
            override suspend fun steeringTarget(chat: String): StudioSteeringTarget = error("No steering")
            override suspend fun validateCreatorInput(chat: String, origin: StudioQueuedOrigin.Creator) {
                assertEquals(record.id, chat)
                validated = origin
            }
        }
        val host = RunHost(mutableListOf())
        var delivered: StudioTurnRequest? = null
        host.execute = {
            delivered = it
            RunOutcome.Completed
        }
        val dispatch = StudioQueueDelivery(
            lazyOf(access),
            lazyOf(host),
            lazy { error("Do not capture creator text as human authority") },
            StudioRunCoordinator(RunProfile(this), RunClock),
            StudioWorktrees(ConfigurationRegistry(), NoAgentTools),
            queue,
        )
        dispatch.queuedRun(record.id, saved, null, StudioRunSubmission()) {}
        val sent = checkNotNull(delivered)
        assertEquals<StudioQueuedOrigin?>(saved.origin, validated)
        assertEquals(StudioTurnOrigin.Conversation, sent.origin)
        assertEquals(setOf(cause), sent.causes)
        assertFalse(sent.isUserPrompt)
        assertEquals(null, sent.userInstruction)
        assertEquals(saved.configuration, sent.queuedConfiguration)
        val future = record.copy(target = record.target?.copy(model = ModelId("future")))
        assertEquals(sent, sent.withCurrentSettings(future))
        assertFailsWith<IllegalStateException> {
            dispatch.queuedRun(record.id, saved.copy(text = "Forged"), null, StudioRunSubmission()) {}
        }
    }

    @Test
    fun `cancelling the caller only detaches from an accepted profile operation`() = runTest {
        val released = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var isFinished = false
        val delivery = StudioConversationDelivery(
            StudioHelperAttempts(ChecklistTestStores()),
            lazy { error("Unused runtime") },
            CapturedCreatorQueue(),
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

private class CapturedCreatorQueue : StudioQueuePort {
    var saved = StudioChatQueue()
    override suspend fun snapshot(chat: String): StudioChatQueue = saved
    override fun isPending(chat: String, request: String): Boolean = saved.messages.any { it.id == request }
    override suspend fun enqueue(
        chat: String,
        message: StudioQueuedMessage,
        environment: StudioChatEnvironment?,
        isCorrection: Boolean,
    ) {
        saved = saved.copy(
            messages = saved.messages + message,
            creatorMessages = saved.creatorMessages + (message.id to message),
        )
    }
}
