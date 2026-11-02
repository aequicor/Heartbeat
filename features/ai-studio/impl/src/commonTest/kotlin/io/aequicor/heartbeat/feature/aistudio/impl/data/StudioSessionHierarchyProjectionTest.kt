package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSessionRole
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.toUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionRoleUi
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioSessionHierarchyProjectionTest {
    private val root = StudioChatRecord("root", "Root", Instant.DISTANT_PAST)
    private val rootContext = LogicalSessionContext(LogicalSessionId(root.id), SessionKind.Session)

    @Test
    fun `canonical full chats and executors preserve separate creator root and owner through UI transport`() {
        val child = chat("child", rootContext.subsession(LogicalSessionId("child")))
        val childContext = rootContext.subsession(LogicalSessionId("child"))
        val grandchild = chat("grandchild", childContext.subsession(LogicalSessionId("grandchild")))
        val agent = chat("agent", childContext.subagent(LogicalSessionId("agent")))
        val projected = resolvedStudioIdentities(listOf(root, child, grandchild, agent)).associate {
            it.id to it.sessionHierarchy().toUi()
        }
        assertEquals(SessionRoleUi.Session, projected.getValue("root").role)
        val nested = projected.getValue("grandchild")
        assertEquals("root", nested.rootSessionId)
        assertEquals("child", nested.creatorSessionId)
        assertNull(nested.ownerSessionId)
        assertTrue(nested.isHumanInteractive)
        val executor = projected.getValue("agent")
        assertEquals(SessionRoleUi.Subagent, executor.role)
        assertEquals("root", executor.rootSessionId)
        assertEquals("child", executor.ownerSessionId)
        assertFalse(executor.isHumanInteractive)
    }

    @Test
    fun `legacy broken ancestry keeps human capability without inventing presentation ownership`() {
        val legacy = root.copy(
            id = "legacy",
            helper = StudioHelperIdentity(ActionId("old-work"), "missing", null, TrustLevel.Ask),
        )
        val canonical = resolvedStudioIdentities(listOf(root, legacy)).last()
        val hierarchy = canonical.sessionHierarchy()
        assertEquals(StudioSessionRole.LegacyInteractive, hierarchy.role)
        assertTrue(hierarchy.toUi().isHumanInteractive)
        assertEquals("MissingCreator", hierarchy.identityIssue)
        assertNull(hierarchy.rootSessionId)
        assertNull(hierarchy.creatorSessionId)
        assertNull(hierarchy.ownerSessionId)
    }

    @Test
    fun `future duplicate and mismatched records always emit explicit unknown instead of a legacy DTO`() {
        val future = root.copy(identity = StudioSessionIdentity(StudioIdentityOrigin.UserConversation, rootContext, 2))
        val mismatch = chat("different", rootContext)
        val sets = listOf(listOf(future), listOf(root, root), listOf(mismatch))
        for (rows in sets) {
            for (record in resolvedStudioIdentities(rows)) {
                val hierarchy = record.sessionHierarchy()
                assertEquals(StudioSessionRole.Unknown, hierarchy.role)
                assertFalse(hierarchy.toUi().isHumanInteractive)
                assertNull(hierarchy.rootSessionId)
                assertNull(hierarchy.ownerSessionId)
            }
        }
    }

    private fun chat(id: String, context: LogicalSessionContext): StudioChatRecord = root.copy(
        id = id,
        identity = StudioSessionIdentity(StudioIdentityOrigin.AgentCreated, context),
    )

    @Test
    fun `noninteractive origin never leaks full chat links into an unknown presentation role`() {
        val internal = root.copy(identity = StudioSessionIdentity(StudioIdentityOrigin.InternalExecution, rootContext))
        val canonical = resolvedStudioIdentities(listOf(internal)).single()
        val hierarchy = canonical.sessionHierarchy()
        assertEquals(StudioSessionRole.Unknown, hierarchy.role)
        assertFalse(hierarchy.toUi().isHumanInteractive)
        assertNull(hierarchy.rootSessionId)
        assertNull(hierarchy.creatorSessionId)
        assertNull(hierarchy.ownerSessionId)
    }

    @Test
    fun `a known full subsession retains human rights when its creator disappears without granting links`() {
        val child = chat("child", rootContext.subsession(LogicalSessionId("child")))
        val canonical = resolvedStudioIdentities(listOf(child)).single()
        val hierarchy = canonical.sessionHierarchy()
        assertEquals(StudioSessionRole.Subsession, hierarchy.role)
        assertTrue(hierarchy.toUi().isHumanInteractive)
        assertEquals("UnresolvedController", hierarchy.identityIssue)
        assertNull(hierarchy.rootSessionId)
        assertNull(hierarchy.creatorSessionId)
    }
}
