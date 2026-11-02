package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StudioPermissionRelaysTest {
    @Test
    fun `owner projection consumes only the exact issued appearance once`() = runTest {
        val f = Fixture()
        f.prepare()
        val permission = f.project().single()
        val relay = assertNotNull(permission.relay)
        assertEquals("owner", permission.sessionId)
        assertEquals(relay.nonce, permission.requestId)
        assertEquals(listOf("once"), permission.options.map { it.id })
        assertFalse(f.relays.enter(relay.copy(nonce = "forged"), f.decision))
        assertTrue(f.relays.enter(relay, f.decision))
        assertFalse(f.relays.enter(relay, f.decision))
        assertFalse(f.relays.enter(relay, f.decision.copy(option = PermissionOptionId("always"))))
    }

    @Test
    fun `restart or changed pending options revoke old appearances`() = runTest {
        val f = Fixture()
        f.prepare()
        val old = assertNotNull(f.project().single().relay)
        val restarted = f.newRelays()
        assertFalse(restarted.enter(old, f.decision))
        val fresh = restarted.project(f.child.id, f.native.state.value, f.native, emptyList()).single().relay!!
        assertNotEquals(old.nonce, fresh.nonce)
        f.native.state.value = f.pending.copy(requests = listOf(f.permission.copy(title = "Changed action")))
        val changed = f.project().single().relay!!
        assertNotEquals(old.nonce, changed.nonce)
        assertFalse(f.relays.enter(old, f.decision))
        assertFalse(restarted.enter(fresh, f.decision))
    }

    @Test
    fun `same visible question with changed action binding cannot consume an old relay`() = runTest {
        val f = Fixture()
        f.prepare()
        val old = f.project().single().relay!!
        f.native.state.value = f.pending.copy(requests = listOf(f.permission.copy(operationBinding = "operation-B")))
        assertFalse(f.relays.enter(old, f.decision))
        val fresh = f.project().single().relay!!
        assertEquals(old.permission.title, fresh.permission.title)
        assertEquals(old.permission.options, fresh.permission.options)
        assertNotEquals(old.nonce, fresh.nonce)
        assertTrue(f.relays.enter(fresh, f.decision))
    }

    @Test
    fun `retirement or missing owner denies an otherwise matching native question`() = runTest {
        val f = Fixture()
        f.prepare()
        val relay = f.project().single().relay!!
        f.records.store.set(
            ChatsKey,
            listOf(f.owner, f.child.copy(executor = f.child.executor!!.copy(isRetired = true))),
        )
        assertFalse(f.relays.enter(relay, f.decision))
        f.records.store.set(ChatsKey, listOf(f.child))
        assertFalse(f.relays.enter(relay, f.decision))
    }

    private class Fixture {
        val stores = ChecklistTestStores()
        val native = NativeHelperFixture(stores)
        val context = LogicalSessionContext(LogicalSessionId("owner"), SessionKind.Session)
        val owner = StudioChatRecord("owner", "Owner", native.record.updatedAt)
        val child = native.record.copy(
            identity = StudioSessionIdentity(
                StudioIdentityOrigin.ExplicitSubagent,
                context.subagent(LogicalSessionId("helper")),
            ),
            executor = StudioExecutorState("instance"),
        )
        val records = object : StudioChatRecords {
            override val store = stores.keyValue(ChatSpec)
            override val lock = Mutex()
        }
        val permission = PermissionRequest(
            PermissionRequestId("ask"),
            native.turn.id,
            "Write file",
            listOf(
                PermissionOption(PermissionOptionId("once"), "Once", isOneTime = true),
                PermissionOption(PermissionOptionId("always"), "Always"),
            ),
        )
        val pending = ActiveSessionState.AwaitingUserAction(native.turn.copy(outcome = null), listOf(permission))
        val decision = PermissionDecision(permission.turn, permission.id, PermissionOptionId("once"))
        val access = object : StudioHelperAccess {
            override suspend fun helperRecord(helper: HelperId) = records.record(helper.value)
            override suspend fun openHelper(record: StudioChatRecord): ActiveSession = error("Never open")
            override suspend fun liveHelper(helper: HelperId): ActiveSession = native
        }
        val relays = newRelays()
        fun newRelays() = StudioPermissionRelays(records, native.attempts, lazyOf(access))
        suspend fun prepare() {
            records.store.set(ChatsKey, listOf(owner, child))
            native.prepare()
            native.state.value = pending
        }
        suspend fun project() = relays.project(child.id, native.state.value, native, emptyList())
    }
}
