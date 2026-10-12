package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

class StudioHelperMetadataTest {
    private val at = Instant.fromEpochSeconds(10)
    private val owner = ActionId("harness_action")
    private val session = SessionRef(EngineId("pi"), SessionSourceId("store"), "native")

    @Test
    fun `durable ref and unresolved request are visible before native acceptance and after reopen`() = runTest {
        val fixture = HelperMetadataFixture()
        val id = fixture.records.createHelper(input())
        val pending = HelperPrompt(RequestId("first"), "Private")
        fixture.attempts.prepare(id, pending)
        fixture.store.set(ChatsKey, fixture.store.get(ChatsKey).orEmpty().map { it.copy(ref = session) })
        val restored = fixture.records()
        val metadata = restored.helperMetadata(session)
        assertEquals(id, metadata?.id)
        assertEquals(owner, metadata?.owner)
        assertEquals(pending.request, metadata?.lastRequest)
        assertEquals(metadata, restored.helperMetadata(id))
        assertEquals(StudioHelperPhase.Preparing, fixture.attempts.receipt(id, pending.request)?.phase)
        assertNull(restored.helperMetadata(session.copy(source = SessionSourceId("different"))))
        assertNull(restored.helperMetadata(session.copy(engine = EngineId("different"))))
    }

    @Test
    fun `parentless helper defers target to host and persists Ask regardless of requested cap`() = runTest {
        val fixture = HelperMetadataFixture()
        val id = fixture.records.createHelper(input().copy(trustCap = TrustLevel.Full))
        val record = fixture.store.get(ChatsKey).orEmpty().single()
        assertEquals(id.value, record.id)
        assertNull(record.target)
        assertNull(record.ref)
        val selected = EngineTarget(session.engine, EngineBindingId("default"), ModelId("model"))
        val defaults = RunSettings("", ReasoningEffort.Medium, ApprovalMode.AutoApprove)
        val settings = hostRunSettings(record, null, defaults)
        assertEquals(selected, turnTarget(settings.modelId, record.target, selected))
        assertNull(turnTarget(settings.modelId, record.target, null))
        assertEquals(TrustLevel.Ask, record.helper?.trustCap)
        assertEquals(owner, fixture.records().ownedHelpers(owner).single().owner)
    }

    @Test
    fun `pages are owner filtered ordered bounded and include old completed helpers`() = runTest {
        val fixture = HelperMetadataFixture()
        val records = (0..129).reversed().map { index ->
            newStudioHelperRecord(index.toString().padStart(3, '0'), at, input(), null)
                .copy(lastRunRequest = RequestId("completed"))
        }
        val foreign = newStudioHelperRecord("foreign", at, input().copy(owner = ActionId("other")), null)
        fixture.store.set(ChatsKey, records + foreign + StudioChatRecord("ordinary", "Chat", at))
        val first = fixture.records.ownedHelpers(owner, limit = 128)
        val next = fixture.records().ownedHelpers(owner, after = first.last().id)
        assertEquals(128, first.size)
        assertEquals(listOf("128", "129"), next.map { it.id.value })
        assertEquals(130, (first + next).map { it.id }.distinct().size)
        assertEquals(RequestId("completed"), first.first().lastRequest)
        assertFailsWith<IllegalArgumentException> { fixture.records.ownedHelpers(owner, limit = 129) }
    }

    @Test
    fun `ordinary or ambiguous stored references cannot claim a helper owner`() = runTest {
        val fixture = HelperMetadataFixture()
        val ordinary = StudioChatRecord("ordinary", "Chat", at, ref = session)
        fixture.store.set(ChatsKey, listOf(ordinary))
        assertNull(fixture.records.helperMetadata(session))
        assertFalse(fixture.records.isHelper(session))
        val helper = newStudioHelperRecord("helper", at, input(), null).copy(ref = session)
        fixture.store.set(ChatsKey, listOf(ordinary, helper))
        assertFailsWith<IllegalStateException> { fixture.records.helperMetadata(session) }
        fixture.store.set(ChatsKey, listOf(helper, helper.copy(title = "Duplicate")))
        assertFailsWith<IllegalStateException> { fixture.records.ownedHelpers(owner) }
        assertFailsWith<IllegalStateException> { fixture.records.helperMetadata(HelperId("helper")) }
    }

    @Test
    fun `parent lookup retains actual checkout and explicit route`() = runTest {
        val fixture = HelperMetadataFixture()
        val target = EngineTarget(session.engine, EngineBindingId("binding"), ModelId("model"))
        val parent = StudioChatRecord(
            "parent",
            "Parent",
            at,
            ref = session,
            projectId = "source",
            executionWorkspace = WorkspaceRef("checkout"),
        )
        fixture.store.set(ChatsKey, listOf(parent))
        val id = fixture.records.createHelper(input().copy(parent = session, target = target))
        val child = fixture.store.get(ChatsKey).orEmpty().single { it.id == id.value }
        assertEquals(WorkspaceRef("checkout"), child.executionWorkspace)
        assertEquals(target, child.target)
        assertEquals(session, fixture.records.helperMetadata(id)?.parent)
        assertNull(child.ref)
    }

    private fun input() = HelperCreateRequest(owner, null, null, null, "Private title", TrustLevel.Ask)
}

private class HelperMetadataFixture {
    private val stores = ChecklistTestStores()
    val store = stores.keyValue(ChatSpec)
    val attempts = StudioHelperAttempts(stores)
    val records = records()

    fun records() = EngineStudioHelperChatRecords(
        stores,
        object : StudioHelperChatWriter {
            override suspend fun saveHelper(record: StudioChatRecord) {
                store.set(ChatsKey, store.get(ChatsKey).orEmpty() + record)
            }
        },
        object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe() = flowOf(emptyList<LocalWorkspace>())
            override suspend fun register(directory: String): LocalWorkspace = error("Unexpected registration")
            override suspend fun resolve(ref: WorkspaceRef): String = "/test/workspace"
        },
        Clock.System,
        StudioHelperAttempts(stores),
    )
}
