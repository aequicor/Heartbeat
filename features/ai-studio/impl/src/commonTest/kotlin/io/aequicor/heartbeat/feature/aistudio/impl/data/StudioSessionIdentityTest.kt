package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioSessionIdentityTest {
    private val at = Instant.fromEpochSeconds(10)
    private val root = chat("root")

    @Test
    fun `legacy interactive contracts preserve full chats and flatten only presentation ancestry`() {
        val first = helper("first", root)
        val second = helper("second", first)
        val original = listOf(second, root, first)
        val migrated = resolvedStudioIdentities(original)
        val child = assertNotNull(migrated.first().logicalSession)
        assertEquals(SessionKind.Subsession, child.kind)
        assertEquals(LogicalSessionId(root.id), child.root)
        assertEquals(LogicalSessionId(first.id), child.creator)
        assertTrue(child.isHumanInteractive)
        assertEquals(StudioIdentityOrigin.LegacyInteractiveHelper, migrated.first().identity?.origin)
        assertEquals(migrated, resolvedStudioIdentities(migrated))
        original.zip(migrated).forEach { (old, new) -> assertEquals(old, new.copy(identity = null)) }
        assertEquals(migrated, Json.decodeFromString<List<StudioChatRecord>>(Json.encodeToString(migrated)))
    }

    @Test
    fun `missing cyclic and duplicate ancestry grant no owner`() {
        val missing = helper("missing", root.copy(id = "gone"))
        val a = helper("a", root.copy(id = "b"))
        val b = helper("b", a)
        val migrated = resolvedStudioIdentities(listOf(missing, a, b, root, root))
        assertTrue(migrated.all { it.logicalSession == null })
        assertTrue(migrated.all { it.identity?.origin == StudioIdentityOrigin.Unresolved })
    }

    @Test
    fun `cross project and conflicting native creator references remain explicit`() {
        val child = helper("child", root).copy(projectId = "foreign")
        val impostor = helper("impostor", root).copy(
            helper = StudioHelperIdentity(ActionId("work"), root.id, native("other"), TrustLevel.Ask),
        )
        val migrated = resolvedStudioIdentities(listOf(root, child, impostor))
        assertEquals("ProjectMismatch", migrated[1].identityIssue)
        assertEquals("CreatorReferenceMismatch", migrated[2].identityIssue)
    }

    @Test
    fun `known subagent keeps its owner while parentless legacy execution stays unresolved`() {
        val context = LogicalSessionContext(LogicalSessionId(root.id), SessionKind.Session)
        val agent = helper("agent", root).copy(
            identity = StudioSessionIdentity(
                StudioIdentityOrigin.AgentCreated,
                context.subagent(LogicalSessionId("agent")),
            ),
        )
        val parentless = helper("internal", root).copy(
            helper = StudioHelperIdentity(ActionId("work"), null, null, TrustLevel.Ask),
        )
        val migrated = resolvedStudioIdentities(listOf(agent, parentless, root))
        assertTrue(migrated.first().isSubagent)
        assertEquals(agent.identity, migrated.first().identity)
        assertNull(migrated[1].logicalSession)
        assertEquals("LegacyParentlessExecution", migrated[1].identityIssue)
    }

    @Test
    fun `unsupported versions and changed owners cannot become an ordinary root`() {
        val context = LogicalSessionContext(LogicalSessionId(root.id), SessionKind.Session)
        val unknown = root.copy(
            identity = StudioSessionIdentity(StudioIdentityOrigin.UserConversation, context, version = 2),
        )
        val child = helper("child", root).copy(
            identity = StudioSessionIdentity(
                StudioIdentityOrigin.AgentCreated,
                context.subagent(LogicalSessionId("different-id")),
            ),
        )
        val migrated = resolvedStudioIdentities(listOf(unknown, child))
        assertEquals(unknown.identity, migrated.first().identity)
        assertNull(migrated.first().logicalSession)
        assertEquals("UnsupportedIdentityVersion", migrated.first().identityIssue)
        assertEquals("IdentityMismatch", migrated[1].identityIssue)
    }

    @Test
    fun `damaged and future identity payloads never prevent reading healthy conversations`() {
        val payloads = listOf(
            """
            {"version":1,"origin":"AgentCreated","context":{
            "id":{"value":"bad0"},"kind":"Subsession","root":{"value":"root"}}}
            """.trimIndent(),
            """{"version":2,"origin":"FutureOrigin","context":{"kind":"FutureKind"},"extra":{"keep":true}}""",
            """"malformed envelope"""",
        ).map(Json::parseToJsonElement)
        val rows = listOf(
            Json.encodeToJsonElement(StudioChatRecord.serializer(), root),
        ) + payloads.mapIndexed { index, raw ->
            val row = Json.encodeToJsonElement(StudioChatRecord.serializer(), chat("bad$index")) as JsonObject
            JsonObject(row + ("identity" to raw))
        }
        val decoded = Json.decodeFromString<List<StudioChatRecord>>(JsonArray(rows).toString())
        assertEquals(root, decoded.first())
        val migrated = resolvedStudioIdentities(decoded)
        assertNotNull(migrated.first().logicalSession)
        assertTrue(migrated.drop(1).all { it.logicalSession == null && it.identityIssue != null })
        assertEquals(payloads, migrated.drop(1).map { it.identity?.raw })
        assertEquals(migrated, resolvedStudioIdentities(migrated))
        assertEquals(migrated, Json.decodeFromString<List<StudioChatRecord>>(Json.encodeToString(migrated)))
    }

    private fun chat(id: String) = StudioChatRecord(id, id, at, ref = native(id), projectId = "project")

    private fun helper(id: String, parent: StudioChatRecord) = chat(id).copy(
        helper = StudioHelperIdentity(ActionId("work-$id"), parent.id, parent.ref, TrustLevel.Ask),
    )

    private fun native(id: String) = SessionRef(EngineId("test"), SessionSourceId("local"), id)
}
