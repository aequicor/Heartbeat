package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
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

class StudioHumanConversationAccessTest {
    private val rootContext = LogicalSessionContext(LogicalSessionId("root"), SessionKind.Session)
    private val root = chat("root")

    @Test
    fun `unresolved legacy ancestry preserves prior human input without granting logical ownership`() {
        val parentless = chat("old").copy(
            helper = StudioHelperIdentity(ActionId("work"), null, null, TrustLevel.Ask),
        )
        val broken = parentless.copy(
            id = "broken",
            helper = parentless.helper?.copy(parentChatId = "missing"),
        )
        val records = resolvedStudioIdentities(listOf(parentless, broken))
        records.forEach {
            assertNull(it.logicalSession)
            assertTrue(it.isHumanConversation)
            assertEquals(it, it.requireHumanConversation())
        }
        val restarted = Json.decodeFromString<List<StudioChatRecord>>(Json.encodeToString(records))
        assertEquals(records, resolvedStudioIdentities(restarted))
        assertTrue(restarted.all { it.isHumanConversation })
    }

    @Test
    fun `a missing creator does not demote a known full subsession into an executor`() {
        val child = chat("child").copy(
            identity = StudioSessionIdentity(
                StudioIdentityOrigin.AgentCreated,
                rootContext.subsession(LogicalSessionId("child")),
            ),
        )
        val missingCreator = resolvedStudioIdentities(listOf(child)).single()
        assertEquals("UnresolvedController", missingCreator.identityIssue)
        assertNull(missingCreator.logicalSession)
        assertTrue(missingCreator.isHumanConversation)
    }

    @Test
    fun `future malformed and duplicate identities cannot gain human rights from a helper marker`() {
        val raw = listOf(
            """{"version":2,"origin":"LegacyInteractiveHelper"}""",
            """{"version":1,"origin":"AgentCreated"}""",
            """{"version":1,"origin":"LegacyInteractiveHelper","context":{"kind":"FutureKind"}}""",
            """{"version":1,"origin":"InternalExecution"}""",
        )
        val unknown = raw.mapIndexed { index, payload ->
            chat("unknown-$index").copy(
                helper = StudioHelperIdentity(ActionId("work"), "root", null, TrustLevel.Ask),
                identity = StudioSessionIdentity(Json.parseToJsonElement(payload)),
            )
        }
        val duplicate = root.copy(identity = StudioSessionIdentity(StudioIdentityOrigin.UserConversation, rootContext))
        val records = resolvedStudioIdentities(unknown + listOf(duplicate, duplicate))
        records.forEach {
            assertFalse(it.isHumanConversation)
            assertFailsWith<EngineException> { it.requireHumanConversation() }
        }
    }

    @Test
    fun `direct permission answers for an executor never open its handle or invalidate human authority`() = runTest {
        val agent = chat("agent").copy(
            identity = StudioSessionIdentity(
                StudioIdentityOrigin.AgentCreated,
                rootContext.subagent(LogicalSessionId("agent")),
            ),
            humanAuthority = StudioHumanAuthority(),
        )
        val store = ChecklistTestStore(ChatSpec).apply { set(ChatsKey, listOf(root, agent)) }
        var handlesRead = 0
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(NoAgentTools)) {
            handlesRead++
            null
        }
        assertFailsWith<EngineException> { authority.respond("agent", "request", "allow", null) }
        val captured = StudioPermission("agent", "request", "Permission", emptyList())
        assertFailsWith<EngineException> { authority.respond(captured, "allow", null) }
        assertEquals(0, handlesRead)
        assertEquals(agent.humanAuthority, store.get(ChatsKey)?.last()?.humanAuthority)
    }

    @Test
    fun `executor metadata edits never release or write while a full subsession remains editable`() = runTest {
        val agent = chat("agent").copy(
            identity = StudioSessionIdentity(
                StudioIdentityOrigin.AgentCreated,
                rootContext.subagent(LogicalSessionId("agent")),
            ),
        )
        val child = chat("child").copy(
            identity = StudioSessionIdentity(
                StudioIdentityOrigin.AgentCreated,
                rootContext.subsession(LogicalSessionId("child")),
            ),
        )
        val store = ChecklistTestStore(ChatSpec).apply { set(ChatsKey, listOf(root, agent, child)) }
        val records = object : StudioChatRecords {
            override val store = store
            override val lock = Mutex()
        }
        var releases = 0
        assertFailsWith<EngineException> {
            records.editConversation("agent", SessionEdit.SetArchived(true), false) { releases++ }
        }
        assertEquals(0, releases)
        assertEquals(listOf(root, agent, child), store.get(ChatsKey))
        records.editConversation("child", SessionEdit.Rename("New title"), false) { releases++ }
        assertEquals("New title", store.get(ChatsKey)?.last()?.title)
        assertEquals(0, releases)
    }

    private fun chat(id: String) = StudioChatRecord(id, id, Instant.fromEpochSeconds(0))
}
