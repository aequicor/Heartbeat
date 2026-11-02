package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionControl
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioExecutorLifecycleTest {
    private val executor = StudioChatRecord(
        "executor",
        "Worker",
        Instant.fromEpochSeconds(10),
        identity = StudioSessionIdentity(StudioIdentityOrigin.InternalExecution),
        executor = StudioExecutorState("incarnation-1"),
    )

    @Test
    fun `retirement rejects prepared and new work after restart`() = runTest {
        val fixture = Fixture()
        fixture.records.store.set(ChatsKey, listOf(executor))
        val helper = HelperId(executor.id)
        val request = RequestId("work")
        fixture.attempts.prepareDelivery(
            helper,
            HelperPrompt(request, "Work"),
            StudioAttemptPolicy.GraphWork(RequestId("root")),
        )
        val binding = fixture.lifecycle.retire(executor.id)!!
        val saved = fixture.records.record(executor.id)
        assertTrue(saved.executor!!.isRetired)
        assertFalse(StudioExecutorAuthority(fixture.records).permits(binding))
        assertTrue(Json.decodeFromString<StudioChatRecord>(Json.encodeToString(saved)).executor!!.isRetired)
        val restarted = StudioHelperAttempts(fixture.stores)
        assertFailsWith<IllegalStateException> { restarted.begin(helper, request, "sender") }
        assertFailsWith<IllegalStateException> {
            restarted.prepareDelivery(
                helper,
                HelperPrompt(RequestId("retry"), "Work"),
                StudioAttemptPolicy.GraphWork(RequestId("root")),
            )
        }
        assertNull(restarted.receipt(helper, request)?.nativeEntryClaim)
    }

    @Test
    fun `full conversation stop never acquires permanent execution retirement`() = runTest {
        val fixture = Fixture()
        val full = StudioChatRecord("full", "Chat", executor.updatedAt)
        fixture.records.store.set(ChatsKey, listOf(full))
        assertNull(fixture.lifecycle.retire(full.id))
        assertTrue(fixture.control.fenced.isEmpty())
        assertTrue(
            fixture.attempts.prepareConversation(
                HelperId(full.id),
                HelperPrompt(RequestId("human"), "New request"),
            ).isNew,
        )
    }

    @Test
    fun `replacement cannot restore authority to the old instance and missing metadata denies admission`() = runTest {
        val fixture = Fixture()
        fixture.records.store.set(ChatsKey, listOf(executor))
        val old = executor.executorBinding()!!
        assertTrue(StudioExecutorAuthority(fixture.records).permits(old))
        val replacement = executor.copy(executor = StudioExecutorState("incarnation-2"))
        fixture.records.store.set(ChatsKey, listOf(replacement))
        assertFalse(StudioExecutorAuthority(fixture.records).permits(old))
        assertTrue(StudioExecutorAuthority(fixture.records).permits(replacement.executorBinding()!!))
        assertFailsWith<IllegalStateException> { executor.copy(executor = null).requireLiveExecutor() }
        fixture.records.store.set(ChatsKey, emptyList())
        assertFalse(StudioExecutorAuthority(fixture.records).permits(old))
    }

    @Test
    fun `orphaned executor remains stoppable without regaining admission rights`() = runTest {
        val fixture = Fixture()
        val owner = LogicalSessionContext(LogicalSessionId("owner"), SessionKind.Session)
        val orphan = executor.copy(
            identity = StudioSessionIdentity(
                StudioIdentityOrigin.ExplicitSubagent,
                owner.subagent(LogicalSessionId(executor.id)),
            ),
        )
        fixture.records.store.set(ChatsKey, listOf(orphan))
        val binding = orphan.executorBinding()!!
        assertFalse(StudioExecutorAuthority(fixture.records).permits(binding))
        var stops = 0
        fixture.lifecycle.cancel(executor.id, pause = {}) { stops++ }
        assertEquals(2, stops)
        assertTrue(fixture.records.record(executor.id).executor!!.isRetired)
        assertEquals(listOf(binding), fixture.control.fenced)
        assertFalse(StudioExecutorAuthority(fixture.records).permits(binding))
    }

    @Test
    fun `uncertain initial Stop is retried after draining without undoing retirement`() = runTest {
        val fixture = Fixture()
        fixture.records.store.set(ChatsKey, listOf(executor))
        val binding = fixture.lifecycle.retire(executor.id)!!
        var stops = 0
        fixture.lifecycle.stop(binding) {
            assertTrue(fixture.records.record(executor.id).executor!!.isRetired)
            stops++
            if (stops == 1) error("native acknowledgement pending")
        }
        assertEquals(2, stops)
        assertEquals(listOf(binding), fixture.control.drained)
    }

    private class Fixture {
        val stores = ChecklistTestStores()
        val records = object : StudioChatRecords {
            override val store = stores.keyValue(ChatSpec)
            override val lock = Mutex()
        }
        val attempts = StudioHelperAttempts(stores)
        val control = Control()
        val lifecycle = StudioExecutorLifecycle(records, control)
    }

    private class Control : SessionExecutionControl {
        val fenced = mutableListOf<SessionExecutionBinding>()
        val drained = mutableListOf<SessionExecutionBinding>()
        override suspend fun fence(binding: SessionExecutionBinding, persist: suspend () -> Unit) {
            persist()
            fenced += binding
        }
        override suspend fun awaitAdmissions(binding: SessionExecutionBinding) {
            drained += binding
        }
        override suspend fun references(binding: SessionExecutionBinding): Set<SessionRef> = emptySet()
    }
}
