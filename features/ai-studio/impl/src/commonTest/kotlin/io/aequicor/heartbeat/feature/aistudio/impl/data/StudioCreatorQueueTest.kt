package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationAttemptPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationMessage
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatQueue
import io.aequicor.heartbeat.feature.aistudio.api.StudioMessageQueues
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedDelivery
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedOrigin
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StudioCreatorQueueTest {
    private val native = NativeHelperFixture()
    private val creator = StudioQueuedMessage(
        "creator",
        "Agent input",
        emptyList(),
        DefaultRunSettings,
        origin = StudioQueuedOrigin.Creator(LogicalSessionId("owner"), native.ref, RequestId("cause")),
        configuration = StudioQueuedConfiguration(native.target, "high", null),
        directives = listOf("Agent origin"),
    )
    private val human = StudioQueuedMessage("human", "Human input", emptyList(), DefaultRunSettings, "Human input")

    @Test
    fun `busy human creator and later human share FIFO and creator retry never replays after restart`() = runTest {
        val stores = ChecklistTestStores()
        val hold = CompletableDeferred<Unit>()
        val host = CreatorQueueHost()
        val queue = owner(stores, host)
        host.execute = { message, accepted ->
            if (message.id == human.id) hold.await()
            accepted()
            queue.settled("chat", message.id, RunOutcome.Completed)
        }
        queue.enqueue("chat", human, null)
        runCurrent()
        queue.enqueue("chat", creator, null)
        queue.enqueue("chat", human.copy(id = "later"), null)
        runCurrent()
        assertEquals(listOf("human"), host.delivered)
        assertEquals(listOf("human", "creator", "later"), queue.snapshot("chat").messages.map { it.id })
        hold.complete(Unit)
        runCurrent()
        assertEquals(listOf("human", "creator", "later"), host.delivered)
        assertTrue(queue.snapshot("chat").messages.isEmpty())
        val restarted = owner(stores, host)
        restarted.enqueue("chat", creator, null)
        runCurrent()
        assertEquals(listOf("human", "creator", "later"), host.delivered)
        assertEquals(creator, restarted.snapshot("chat").creatorMessages[creator.id])
        assertFailsWith<IllegalStateException> { restarted.enqueue("chat", creator.copy(text = "Changed"), null) }
        assertFailsWith<IllegalStateException> {
            restarted.enqueue("chat", creator.copy(origin = StudioQueuedOrigin.Human), null)
        }
    }

    @Test
    fun `creator removal fences delayed dispatch before dropping its row`() = runTest {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        val release = CompletableDeferred<Unit>()
        val host = CreatorQueueHost()
        var nativeEntries = 0
        val queue = owner(stores, host, attempts)
        host.execute = { message, _ ->
            release.await()
            val preparation = attempts.prepareConversation(
                HelperId("chat"),
                HelperPrompt(RequestId(message.id), message.text),
            )
            if (preparation.isNew) nativeEntries++
        }
        queue.enqueue("chat", creator, null)
        runCurrent()
        assertEquals(listOf(creator.id), host.delivered)
        queue.remove("chat", creator.id)
        val restored = StudioHelperAttempts(stores)
        assertTrue(checkNotNull(restored.receipt(HelperId("chat"), RequestId(creator.id))).isFinal)
        assertTrue(queue.snapshot("chat").messages.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertEquals(0, nativeEntries)
        queue.enqueue("chat", creator, null)
        runCurrent()
        assertEquals(listOf(creator.id), host.delivered)
    }

    @Test
    fun `creator cannot resume a paused FIFO or become human correction`() = runTest {
        val host = CreatorQueueHost()
        val queue = owner(ChecklistTestStores(), host)
        queue.pause("chat")
        queue.enqueue("chat", creator, null)
        runCurrent()
        assertTrue(queue.snapshot("chat").isPaused)
        assertTrue(host.delivered.isEmpty())
        queue.correct("chat", creator.id)
        runCurrent()
        assertTrue(host.delivered.isEmpty())
        assertFalse(host.isSteeringRequested)
    }

    @Test
    fun `disabled queue rejects creator input before acknowledgement`() = runTest {
        val queue = owner(
            ChecklistTestStores(),
            CreatorQueueHost(),
            toggles = ChecklistTestToggles().also { it.scheduler = false },
        )
        runCurrent()
        assertFailsWith<IllegalStateException> { queue.enqueue("chat", creator, null) }
        assertTrue(queue.snapshot("chat").messages.isEmpty())
        assertTrue(queue.snapshot("chat").creatorMessages.isEmpty())
    }

    @Test
    fun `lost creator acknowledgement is retired from exact accepted or terminal receipt after restart`() = runTest {
        for (phase in listOf(StudioHelperPhase.Accepted, StudioHelperPhase.Terminal)) {
            val stores = ChecklistTestStores()
            seedLostAck(stores, receipt(phase))
            val host = CreatorQueueHost()
            val queue = owner(stores, host)
            host.execute = { message, accepted ->
                accepted()
                queue.settled("chat", message.id, RunOutcome.Completed)
            }
            assertEquals(listOf(human), queue.snapshot("chat").messages)
            assertEquals(creator, queue.snapshot("chat").creatorMessages[creator.id])
            queue.resume("chat")
            runCurrent()
            assertEquals(listOf(human.id), host.delivered)
            assertTrue(queue.snapshot("chat").messages.isEmpty())
        }
    }

    @Test
    fun `accepted creator still running after restart retains the next input until real admission is idle`() = runTest {
        val stores = ChecklistTestStores()
        val record = seedActiveCreator(stores)
        val attempts = StudioHelperAttempts(stores)
        val admission = StudioHelperAdmission(attempts, emptyHelperAdmissions())
        val profile = RunProfile(
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
        )
        val coordinator = StudioRunCoordinator(profile, RunClock)
        val transport = CreatorQueueHost()
        val queue = owner(stores, transport, attempts, coordinator = coordinator)
        val host = RunHost(mutableListOf())
        val nativeEntries = mutableListOf<RequestId>()
        host.settle = { request, outcome -> settleStudioQueue(queue, request, outcome) }
        host.execute = { request ->
            val admitted = admission.prepareNewTurn(record, request) {
                stores.keyValue(ChatSpec).set(ChatsKey, listOf(record.startedRun(request.request)))
            }
            val gate = checkNotNull(admitted.submission as? StudioHelperSubmission)
            gate.withPreparation(native.ref, null) {
                gate.begin()
                gate.send {
                    nativeEntries += admitted.request
                    attempts.accepted(HelperId("chat"), admitted.request, native.ref, TurnId("human-turn"))
                    admitted.onAccepted()
                    RunOutcome.Completed
                }
            }
        }
        transport.execute = { message, accepted ->
            val request = studioTurnRequest(
                "chat",
                message.text,
                message.settings,
                WorktreeRunKind.Coding,
                RequestId(message.id),
                isUserPrompt = true,
                userInstruction = message.userInstruction,
                onAccepted = accepted,
            )
            coordinator.run(host, request.copy(submission = StudioRunSubmission()), waitForIdle = true)
        }
        queue.resume("chat")
        runCurrent()
        assertTrue(nativeEntries.isEmpty())
        assertEquals(listOf(human), queue.snapshot("chat").messages)
        assertTrue(queue.snapshot("chat").isPaused)
        attempts.terminal(
            HelperId("chat"),
            RequestId(creator.id),
            StudioHelperTerminal(native.ref, native.turn.id, StudioHelperTerminalOutcome.Completed, "Done"),
        )
        queue.resume("chat")
        runCurrent()
        assertEquals(listOf(RequestId(human.id)), nativeEntries)
        assertTrue(queue.snapshot("chat").messages.isEmpty())
    }

    @Test
    fun `restart keeps entered uncertainty but permits exact proven preentry rollback`() = runTest {
        for (entered in listOf(false, true)) {
            val stores = ChecklistTestStores()
            seedLostAck(
                stores,
                receipt(StudioHelperPhase.Submitting).copy(nativeEntryClaim = if (entered) "sender" else null),
            )
            val host = CreatorQueueHost()
            val queue = owner(stores, host)
            val rows = queue.snapshot("chat").messages
            assertEquals(2, rows.size)
            assertEquals(
                if (entered) StudioQueuedDelivery.Uncertain else StudioQueuedDelivery.Pending,
                rows.first().delivery,
            )
            if (entered) {
                queue.resume("chat")
                runCurrent()
                assertTrue(host.delivered.isEmpty())
            } else {
                assertEquals(
                    StudioHelperPhase.NotSubmitted,
                    StudioHelperAttempts(stores).receipt(HelperId("chat"), RequestId(creator.id))?.phase,
                )
            }
        }
    }

    @Test
    fun `conversation status and cancel address a pending human row without stranding the FIFO`() = runTest {
        val stores = ChecklistTestStores()
        stores.keyValue(CreatorQueueSpec).set(
            CreatorQueueKey,
            StudioMessageQueues(mapOf("chat" to StudioChatQueue(messages = listOf(human)))),
        )
        val queue = owner(stores, CreatorQueueHost())
        val record = native.record.copy(
            id = "chat",
            helper = null,
            ref = null,
            identity = StudioSessionIdentity(
                StudioIdentityOrigin.UserConversation,
                LogicalSessionContext(LogicalSessionId("chat"), SessionKind.Session),
            ),
        )
        val attempts = StudioHelperAttempts(stores)
        val dispatch = object : StudioConversationDispatch {
            override suspend fun <T> owned(operation: suspend () -> T): T = operation()
            override fun initialTarget(record: StudioChatRecord): EngineTarget? = record.target
            override fun isPending(child: LogicalSessionId, request: RequestId): Boolean = queue.isPending(
                child.value,
                request.value,
            )
            override suspend fun pendingRequest(child: LogicalSessionId): RequestId? =
                queue.snapshot(child.value).messages.firstOrNull()?.id?.let(::RequestId)
            override suspend fun retirePending(child: LogicalSessionId, request: RequestId) = queue.retireFenced(
                child.value,
                request.value,
            )
            override suspend fun enqueue(
                child: LogicalSessionId,
                message: HostConversationMessage,
                cause: RequestInitiator,
                beforePrepare: suspend (Boolean) -> StudioChatRecord,
            ): Unit = error("No creator enqueue")
        }
        val evidence = StudioConversationEvidence(
            attempts,
            lazy { error("No native work") },
            lazy { error("No native access") },
            dispatch,
        )
        assertEquals(HostConversationAttemptPhase.Queued, evidence.snapshot(record).attempt?.phase)
        assertEquals(
            HostConversationCancellation.NotSubmitted(RequestId(human.id)),
            evidence.cancel(record, RequestId(human.id)),
        )
        assertTrue(queue.snapshot("chat").messages.isEmpty())
        assertTrue(checkNotNull(attempts.receipt(HelperId("chat"), RequestId(human.id))).isFinal)
    }

    private suspend fun seedActiveCreator(stores: ChecklistTestStores): StudioChatRecord {
        seedLostAck(stores, receipt(StudioHelperPhase.Accepted))
        val record = native.record.copy(
            id = "chat",
            helper = null,
            lastRunRequest = RequestId(creator.id),
            hasAttemptJournal = true,
        )
        stores.keyValue(ChatSpec).set(ChatsKey, listOf(record))
        return record
    }

    private suspend fun seedLostAck(stores: ChecklistTestStores, receipt: StudioHelperReceipt) {
        val queued = StudioChatQueue(
            messages = listOf(creator.copy(delivery = StudioQueuedDelivery.Sending), human),
            activeRequest = creator.id,
            creatorMessages = mapOf(creator.id to creator),
        )
        stores.keyValue(CreatorQueueSpec).set(CreatorQueueKey, StudioMessageQueues(mapOf("chat" to queued)))
        stores.keyValue(
            HelperAttemptsSpec,
        ).set(helperReceiptKey(HelperId("chat")), Json.encodeToString(mapOf(creator.id to receipt)))
    }

    private fun receipt(phase: StudioHelperPhase): StudioHelperReceipt {
        val origin = creator.origin as StudioQueuedOrigin.Creator
        val causes = setOf(RequestInitiator(origin.session, origin.request))
        return StudioHelperReceipt(
            RequestId(creator.id), phase, HelperPrompt(RequestId(creator.id), creator.text).fingerprint(),
            session = native.ref.takeUnless { phase == StudioHelperPhase.Submitting },
            turn = native.turn.id.takeUnless { phase == StudioHelperPhase.Submitting },
            preparedSession = native.ref,
            policy = StudioAttemptPolicy.Conversation,
            submissionClaim = "sender", nativeEntryClaim = "sender", isNativeEntryTracked = true, causes = causes,
            executionSnapshot = StudioExecutionSnapshot(
                creator.settings,
                native.target,
                "high",
                null,
                StudioHumanAuthority(),
                causes,
            ),
            terminal = if (phase == StudioHelperPhase.Terminal) {
                StudioHelperTerminal(native.ref, native.turn.id, StudioHelperTerminalOutcome.Completed, "Done")
            } else {
                null
            },
        )
    }

    private fun TestScope.owner(
        stores: ChecklistTestStores,
        host: CreatorQueueHost,
        attempts: StudioHelperAttempts = StudioHelperAttempts(stores),
        toggles: ChecklistTestToggles = ChecklistTestToggles(),
        coordinator: StudioRunCoordinator? = null,
    ): StudioMessageQueueOwner {
        val profile = RunProfile(
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])),
        )
        return StudioMessageQueueOwner(
            stores,
            profile,
            QueueOwnerLauncher,
            lazyOf(host),
            coordinator ?: StudioRunCoordinator(profile, RunClock),
            toggles,
            lazyOf(attempts),
        )
    }
}

private class CreatorQueueHost : StudioQueueHost {
    val delivered = mutableListOf<String>()
    var isSteeringRequested = false
    var execute: suspend (StudioQueuedMessage, suspend () -> Unit) -> Unit = { _, _ -> error("No dispatch expected") }

    override suspend fun queuedRun(
        chat: String,
        message: StudioQueuedMessage,
        environment: StudioChatEnvironment?,
        submission: StudioSubmissionGate,
        accepted: suspend () -> Unit,
    ) {
        delivered += message.id
        execute(message, accepted)
    }

    override suspend fun steeringTarget(chat: String): StudioSteeringTarget {
        isSteeringRequested = true
        error("No human correction expected")
    }

    override suspend fun steerQueued(target: StudioSteeringTarget, message: StudioQueuedMessage): Unit = error(
        "No steering",
    )
}

private val CreatorQueueSpec = KeyValueSpec("studio_message_queue", readPolicy = KeyValueReadPolicy.Strict)
private val CreatorQueueKey = jsonKey("queues", StudioMessageQueues.serializer())
