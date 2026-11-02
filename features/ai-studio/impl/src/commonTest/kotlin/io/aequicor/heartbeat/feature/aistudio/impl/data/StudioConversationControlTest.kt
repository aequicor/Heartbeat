package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationAttempt
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationAttemptPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationMessage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SubsessionCreation
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class StudioConversationControlTest {
    @Test
    fun `creation is idempotent across engine segments and never installs a helper work lease`() = runTest {
        val f = ConversationControlFixture()
        val input = SubsessionCreation(RequestId("create"), "Child", "Review the contract")
        val first = f.control.createSubsession(f.caller("root"), input)
        f.records.edit("root") { copy(ref = native("replacement"), previousRefs = listOf(native("root"))) }
        val repeated = f.control.createSubsession(f.caller("root"), input)

        assertEquals(first.context, repeated.context)
        assertEquals(2, f.records.rows.size)
        assertEquals(1, f.dispatch.messages.size)
        val child = f.records.get(first.context.id.value)
        assertEquals(SessionKind.Subsession, child.logicalSession?.kind)
        assertEquals(LogicalSessionId("root"), child.logicalSession?.root)
        assertEquals(LogicalSessionId("root"), child.logicalSession?.creator)
        assertNull(child.helper)
        assertNull(child.worktreeTaskId)
        assertEquals(WorkspaceRef("project"), child.executionWorkspace)
        assertFailsWith<IllegalStateException> {
            f.control.createSubsession(f.caller("root"), input.copy(initialMessage = "Different work"))
        }
        assertEquals(2, f.records.rows.size)
    }

    @Test
    fun `nested creation keeps one family root and only the direct creator controls it`() = runTest {
        val f = ConversationControlFixture()
        val first = f.control.createSubsession(f.caller("root"), SubsessionCreation(RequestId("first"), "First"))
        f.records.edit(first.context.id.value) { copy(ref = native("first")) }
        val second = f.control.createSubsession(
            f.caller(first.context.id.value),
            SubsessionCreation(RequestId("second"), "Second"),
        )

        assertEquals(LogicalSessionId("root"), second.context.root)
        assertEquals(first.context.id, second.context.creator)
        assertEquals(second.context, f.control.state(f.caller("root"), second.context.id).context)
        assertFailsWith<EngineException> {
            f.control.send(
                f.caller("root"),
                second.context.id,
                HostConversationMessage(RequestId("wrong-owner"), "Run"),
            )
        }
        f.control.send(
            f.caller(first.context.id.value),
            second.context.id,
            HostConversationMessage(RequestId("owned"), "Run"),
        )
        assertEquals(RequestId("owned"), f.dispatch.messages.single().message.request)
    }

    @Test
    fun `later creator messages use the current child configuration and preserve its human instructions`() = runTest {
        val f = ConversationControlFixture()
        f.records.edit(
            "root",
        ) {
            copy(
                humanAuthority = StudioHumanAuthority(
                    listOf(StudioHumanInstruction(RequestId("human-a"), "Do not publish")),
                ),
            )
        }
        val created = f.control.createSubsession(
            f.caller("root"),
            SubsessionCreation(RequestId("initial"), "Child", "Inspect"),
        )
        f.dispatch.finish(created.context.id, RequestId("initial"))
        val replacement = target.copy(model = ModelId("user-selected"))
        f.records.edit(created.context.id.value) {
            copy(
                target = replacement,
                humanAuthority = humanAuthority?.append(RequestId("human-b"), "Use this checkout"),
            )
        }
        f.records.edit(
            "root",
        ) { copy(humanAuthority = humanAuthority?.append(RequestId("human-c"), "Do not delete files")) }
        f.control.send(
            f.caller("root"),
            created.context.id,
            HostConversationMessage(RequestId("next"), "Continue the review"),
        )

        assertEquals(replacement, f.dispatch.messages.last().record.target)
        val authority = f.records.get(created.context.id.value).humanAuthority
        assertEquals(
            listOf("Do not publish", "Do not delete files", "Use this checkout"),
            authority?.messages?.map { it.text },
        )
        assertFalse(authority?.messages?.any { it.text == "Continue the review" } == true)
        assertNull(f.records.get(created.context.id.value).helper)
    }

    @Test
    fun `forged roles wrong workspaces subagents and missing caller evidence cannot allocate conversations`() =
        runTest {
            val f = ConversationControlFixture()
            val rootCaller = f.caller("root")
            val creation = SubsessionCreation(RequestId("create"), "Child")
            val forged = rootCaller.logicalSession!!.subsession(LogicalSessionId("forged"))
            for (caller in listOf(
                rootCaller.copy(logicalSession = null),
                rootCaller.copy(logicalSession = forged),
                rootCaller.copy(workspace = WorkspaceRef("other")),
                rootCaller.copy(request = null),
            )) {
                assertFailsWith<EngineException> { f.control.createSubsession(caller, creation) }
            }
            val agent = rootCaller.logicalSession!!.subagent(LogicalSessionId("agent"))
            f.records.add(
                root("agent").copy(identity = StudioSessionIdentity(StudioIdentityOrigin.AgentCreated, agent)),
            )
            assertFailsWith<EngineException> { f.control.createSubsession(f.caller("agent"), creation) }
            assertEquals(2, f.records.rows.size)
        }

    @Test
    fun `cancel names the exact attempt and a cancelled caller cannot begin control work`() = runTest {
        val f = ConversationControlFixture()
        val child = f.control.createSubsession(
            f.caller("root"),
            SubsessionCreation(RequestId("old"), "Child", "Inspect"),
        )
        f.dispatch.finish(child.context.id, RequestId("old"))
        f.control.send(f.caller("root"), child.context.id, HostConversationMessage(RequestId("new"), "Continue"))
        f.control.cancel(f.caller("root"), child.context.id, RequestId("old"))
        assertEquals(listOf(child.context.id to RequestId("old")), f.observations.stops)
        assertTrue(f.dispatch.isPending(child.context.id, RequestId("new")))

        val ended = Job().apply { cancel() }
        assertFailsWith<CancellationException> {
            f.control.createSubsession(
                f.caller("root").copy(lifetime = ended),
                SubsessionCreation(RequestId("cancelled"), "Other"),
            )
        }
        assertEquals(2, f.records.rows.size)
    }

    @Test
    fun `unreadable creation metadata preserves history and blocks duplicate allocation`() = runTest {
        val f = ConversationControlFixture()
        val input = SubsessionCreation(RequestId("create"), "Child")
        val created = f.control.createSubsession(f.caller("root"), input)
        f.records.edit(created.context.id.value) { copy(subsessionCreation = JsonPrimitive("damaged provenance")) }
        val decoded = Json.decodeFromString<List<StudioChatRecord>>(Json.encodeToString(f.records.rows))
        assertEquals(f.records.rows, decoded)
        assertEquals(created.context, f.control.state(f.caller("root"), created.context.id).context)
        assertFailsWith<IllegalStateException> { f.control.createSubsession(f.caller("root"), input) }
        assertEquals(2, f.records.rows.size)
    }
}

private class ConversationControlFixture {
    val records = ConversationControlRecords(listOf(root("root")))
    val dispatch = ConversationControlDispatch()
    val observations = ConversationControlObservations(dispatch)
    val control = EngineStudioConversationControl(lazyOf(records), dispatch, observations, Clock.System)

    fun caller(id: String): AgentToolContext {
        val record = records.get(id)
        return AgentToolContext(
            checkNotNull(record.ref),
            record.resolvedExecutionWorkspace(),
            TurnId("turn-$id"),
            RequestId("parent-$id"),
            logicalSession = checkNotNull(record.logicalSession),
        )
    }
}

private class ConversationControlRecords(initial: List<StudioChatRecord>) : StudioConversationRecords {
    private val lock = Mutex()
    var rows = resolvedStudioIdentities(initial)
        private set
    fun get(id: String): StudioChatRecord = rows.single { it.id == id }
    fun edit(id: String, change: StudioChatRecord.() -> StudioChatRecord) {
        rows = resolvedStudioIdentities(rows.map { if (it.id == id) it.change() else it })
    }
    fun add(record: StudioChatRecord) {
        rows = resolvedStudioIdentities(rows + record)
    }
    override suspend fun <T> editConversations(change: (List<StudioChatRecord>) -> StudioConversationUpdate<T>): T =
        lock.withLock {
            val result = change(rows)
            rows = resolvedStudioIdentities(result.records)
            result.value
        }
}

private data class ControlMessage(val record: StudioChatRecord, val message: HostConversationMessage)

private class ConversationControlDispatch : StudioConversationDispatch {
    val messages = mutableListOf<ControlMessage>()
    private val pending = mutableSetOf<Pair<LogicalSessionId, RequestId>>()
    override suspend fun <T> owned(operation: suspend () -> T): T = operation()
    override fun initialTarget(record: StudioChatRecord): EngineTarget? = record.target
    override fun isPending(child: LogicalSessionId, request: RequestId): Boolean = child to request in pending
    fun finish(child: LogicalSessionId, request: RequestId) {
        pending -= child to request
    }
    override suspend fun enqueue(
        child: LogicalSessionId,
        message: HostConversationMessage,
        cause: RequestInitiator,
        beforePrepare: suspend (Boolean) -> StudioChatRecord,
    ) {
        val previous = messages.firstOrNull { it.record.id == child.value && it.message.request == message.request }
        val record = beforePrepare(previous == null)
        if (previous == null) {
            messages += ControlMessage(record, message)
            pending += child to message.request
        } else {
            check(previous.message == message)
        }
    }
}

private class ConversationControlObservations(private val dispatch: ConversationControlDispatch) :
    StudioConversationObservations {
    val stops = mutableListOf<Pair<LogicalSessionId, RequestId>>()
    override suspend fun snapshot(record: StudioChatRecord): HostConversationSnapshot = HostConversationSnapshot(
        checkNotNull(record.logicalSession),
        record.title,
        record.target,
        dispatch.messages.lastOrNull { it.record.id == record.id }?.message?.let {
            HostConversationAttempt(it.request, HostConversationAttemptPhase.Queued)
        },
    )
    override suspend fun result(record: StudioChatRecord, request: RequestId): HostConversationResult? = null
    override suspend fun cancel(record: StudioChatRecord, request: RequestId): HostConversationCancellation {
        stops += LogicalSessionId(record.id) to request
        return HostConversationCancellation.NotSubmitted(request)
    }
}

private val target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("initial"))
private fun native(id: String) = SessionRef(target.engine, SessionSourceId("local"), id)
private fun root(id: String) = StudioChatRecord(
    id,
    id,
    Instant.fromEpochSeconds(10),
    ref = native(id),
    target = target,
    projectId = "project",
    humanAuthority = StudioHumanAuthority(),
)
