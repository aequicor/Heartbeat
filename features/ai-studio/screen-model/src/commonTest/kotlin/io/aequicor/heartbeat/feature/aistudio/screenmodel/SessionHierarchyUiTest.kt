package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SessionHierarchyUiTest {
    @Test
    fun `older interactive DTO remains compatible while current unknown metadata denies input`() {
        val old = session("old")
        val encoded = StudioViewJson.encodeToJsonElement(SessionUi.serializer(), old) as JsonObject
        val wire = JsonObject(encoded - "hierarchy").toString()
        val decoded = StudioViewJson.decodeFromString<SessionUi>(wire)
        assertNull(decoded.hierarchy)
        assertTrue(decoded.isHumanInteractive)
        assertFalse(old.copy(hierarchy = SessionHierarchyUi()).isHumanInteractive)
        assertFalse(old.copy(hierarchy = SessionHierarchyUi(kind = "subagent")).isHumanInteractive)
        assertTrue(old.copy(hierarchy = SessionHierarchyUi(kind = "legacy_interactive")).isHumanInteractive)
    }

    @Test
    fun `future versions and kinds remain readable without human privileges`() {
        val variants = listOf(
            SessionHierarchyUi(kind = "future_executor", rootSessionId = "owner"),
            SessionHierarchyUi(version = 2, kind = "session", rootSessionId = "owner"),
        )
        for (hierarchy in variants) {
            val original = session("future").copy(hierarchy = hierarchy)
            val wire = StudioViewJson.encodeToString(SessionUi.serializer(), original)
            val decoded = StudioViewJson.decodeFromString<SessionUi>(wire)
            assertEquals(original, decoded)
            assertEquals(SessionRoleUi.Unknown, decoded.hierarchy?.role)
            assertFalse(decoded.isHumanInteractive)
        }
    }

    @Test
    fun `a subsession created by another subsession remains under the family root`() {
        val root = session("root").copy(hierarchy = SessionHierarchyUi(kind = "session", rootSessionId = "root"))
        val first = session("first").copy(
            hierarchy = SessionHierarchyUi(kind = "subsession", rootSessionId = "root", creatorSessionId = "root"),
        )
        val second = session("second").copy(
            parentChatId = "first",
            hierarchy = SessionHierarchyUi(kind = "subsession", rootSessionId = "root", creatorSessionId = "first"),
        )
        val rows = SidebarSessionTree(listOf(root, second, first)).all()
        assertEquals(listOf("root", "second", "first"), rows.map { it.id })
        assertEquals(listOf(0, 1, 1), rows.map { it.depth })
        assertEquals("first", rows[1].hierarchy?.creatorSessionId)
        assertEquals("root", rows[1].hierarchy?.rootSessionId)
    }

    @Test
    fun `explicit unknown ancestry never falls back to the old helper parent`() {
        val root = session("root")
        val variants = listOf(
            SessionHierarchyUi(),
            SessionHierarchyUi(version = 2, kind = "subsession", rootSessionId = "root"),
            SessionHierarchyUi(kind = "subsession", rootSessionId = "missing"),
            SessionHierarchyUi(kind = "legacy_interactive", identityIssue = "MissingCreator"),
        )
        for (hierarchy in variants) {
            val child = session("child").copy(parentChatId = root.id, hierarchy = hierarchy)
            val rows = SidebarSessionTree(listOf(root, child)).all()
            assertEquals(listOf(0, 0), rows.map { it.depth })
            assertEquals(hierarchy, rows.last().hierarchy)
        }
    }

    @Test
    fun `cross project roots and executor roots cannot group a full subsession`() {
        val child = session("child").copy(
            hierarchy = SessionHierarchyUi(kind = "subsession", rootSessionId = "root"),
        )
        val roots = listOf(
            session("root").copy(projectId = "other"),
            session("root").copy(hierarchy = SessionHierarchyUi(kind = "subagent", ownerSessionId = "owner")),
            session("root").copy(hierarchy = SessionHierarchyUi(kind = "subsession", rootSessionId = "elsewhere")),
        )
        for (root in roots) assertEquals(listOf(0, 0), SidebarSessionTree(listOf(root, child)).all().map { it.depth })
    }

    private fun session(id: String): SessionUi = SessionUi(id, id, "project", Instant.DISTANT_PAST)

    @Test
    fun `mixing legacy ancestry and a current root link never creates a third level`() {
        val root = session("root")
        val legacy = session("legacy").copy(parentChatId = root.id)
        val child = session("child").copy(
            hierarchy = SessionHierarchyUi(kind = "subsession", rootSessionId = legacy.id),
        )
        val rows = SidebarSessionTree(listOf(root, legacy, child)).all()
        assertEquals(listOf("root", "legacy", "child"), rows.map { it.id })
        assertEquals(listOf(0, 1, 0), rows.map { it.depth })
        assertEquals(legacy.id, rows.last().hierarchy?.rootSessionId)
        assertTrue(rows.last().isHumanInteractive)
    }
}
