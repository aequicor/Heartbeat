package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperCreateRequest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioHelperChatRecordsTest {
    private val at = Instant.fromEpochSeconds(10)
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "parent")
    private val target = EngineTarget(EngineId("test"), EngineBindingId("binding"), ModelId("model"))
    private val request = HelperCreateRequest(
        ActionId("workflow"),
        session,
        WorkspaceRef("hint"),
        target,
        "Helper",
        TrustLevel.Full,
    )
    private val parent = StudioChatRecord("parent-chat", "Parent", at, ref = session, projectId = "project")

    @Test
    fun `engine replacement keeps owner trust parent and explicit independent reviewer`() {
        val identity = StudioHelperIdentity(request.owner, parent.id, session, TrustLevel.Ask)
        val other = target.copy(engine = EngineId("other"))
        val switched = identity.forEngineSwitch(target, other)
        assertEquals(identity.copy(model = HelperModelOptions(reviewTarget = target)), switched)
        assertEquals(switched, switched.forEngineSwitch(other, target))
        assertEquals(identity, identity.forEngineSwitch(null, other))
        assertEquals(identity, identity.forEngineSwitch(target, target))
        val explicit = identity.copy(model = HelperModelOptions(reviewTarget = other))
        assertEquals(explicit, explicit.forEngineSwitch(target, other))
    }

    @Test
    fun `worktree helper inherits checkout but owns no worktree provisioning or actions`() {
        val record = newStudioHelperRecord(
            "helper",
            at,
            request,
            parent.copy(executionWorkspace = WorkspaceRef("checkout"), worktreeTaskId = "parent-task"),
        )
        assertEquals("project", record.projectId)
        assertEquals(WorkspaceRef("checkout"), record.resolvedExecutionWorkspace())
        assertNull(record.worktreeTaskId)
        assertNull(record.ref)
        assertNull(record.lastRunRequest)
        assertNull(record.configuration)
        assertTrue(record.items.isEmpty())
        assertEquals(target, record.target)
        assertEquals(StudioHelperIdentity(request.owner, parent.id, session, TrustLevel.Full), record.helper)
        assertEquals(record, Json.decodeFromString<StudioChatRecord>(Json.encodeToString(record)))
    }

    @Test
    fun `a helper on another model is reviewed by its parent's model and keeps the chosen effort`() {
        val cheap = target.copy(model = ModelId("mini"))
        val record = newStudioHelperRecord(
            "helper",
            at,
            request.copy(target = cheap, model = HelperModelOptions(reasoningEffort = "low")),
            parent.copy(target = target),
        )
        assertEquals(cheap, record.target)
        assertEquals(HelperModelOptions("low", reviewTarget = target), record.helper?.model)
        assertEquals(record, Json.decodeFromString<StudioChatRecord>(Json.encodeToString(record)))
    }

    @Test
    fun `an explicit review model wins and the parent's own model needs no separate review`() {
        val strong = target.copy(model = ModelId("strong"))
        val explicit = newStudioHelperRecord(
            "helper",
            at,
            request.copy(model = HelperModelOptions(reviewTarget = strong)),
            parent.copy(target = target.copy(model = ModelId("other"))),
        )
        assertEquals(strong, explicit.helper?.model?.reviewTarget)
        val same = newStudioHelperRecord("helper", at, request, parent.copy(target = target))
        assertNull(same.helper?.model?.reviewTarget)
    }

    @Test
    fun `a helper marker saved before model options decodes with defaults`() {
        val legacy = """{"owner":{"value":"workflow"},"parentChatId":null,"parentSession":null,"trustCap":"Ask"}"""
        assertEquals(HelperModelOptions(), Json.decodeFromString<StudioHelperIdentity>(legacy).model)
    }

    @Test
    fun `a turn takes the helper effort only while the model advertises it`() {
        val helper = StudioHelperIdentity(ActionId("a"), null, null, TrustLevel.Ask, HelperModelOptions("low"))
        assertEquals("low", turnEffort(null, helper, listOf("low", "high")) { "high" })
        assertEquals("high", turnEffort(null, helper, listOf("medium", "high")) { "high" })
        assertEquals("high", turnEffort(null, null, listOf("low", "high")) { "high" })
    }

    @Test
    fun `parentless helper retains its marker and explicit workspace with Ask cap after restore`() {
        val record = newStudioHelperRecord("helper", at, request.copy(parent = null), null)
        val restored = Json.decodeFromString<StudioChatRecord>(Json.encodeToString(record))
        assertNull(restored.projectId)
        assertEquals(request.workspace, restored.resolvedExecutionWorkspace())
        assertEquals(StudioHelperIdentity(request.owner, null, null, TrustLevel.Ask), restored.helper)
    }

    @Test
    fun `ordinary parent project or detached scope wins over caller workspace hint`() {
        val inProject = newStudioHelperRecord("helper", at, request, parent)
        assertEquals(WorkspaceRef("project"), inProject.resolvedExecutionWorkspace())
        val detached = newStudioHelperRecord("helper", at, request, parent.copy(projectId = null))
        assertNull(detached.executionWorkspace)
        assertNull(detached.resolvedExecutionWorkspace())
    }

    @Test
    fun `unknown or mismatched parent and unprepared worktree cannot create helper identity`() {
        assertFailsWith<IllegalArgumentException> { newStudioHelperRecord("helper", at, request, null) }
        assertFailsWith<IllegalArgumentException> {
            newStudioHelperRecord("helper", at, request, parent.copy(ref = session.copy(nativeId = "other")))
        }
        assertFailsWith<IllegalArgumentException> {
            newStudioHelperRecord("helper", at, request.copy(parent = null), parent)
        }
        assertFailsWith<IllegalStateException> {
            newStudioHelperRecord("helper", at, request, parent.copy(worktreeTaskId = "pending"))
        }
    }

    @Test
    fun `omitted target inherits durable parent model while explicit target stays unchanged`() {
        val parentTarget = target.copy(model = ModelId("parent-model"))
        val routedParent = parent.copy(target = parentTarget)
        val inherited = newStudioHelperRecord("helper", at, request.copy(target = null), routedParent)
        assertEquals(parentTarget, inherited.target)
        assertEquals(
            parentTarget,
            Json.decodeFromString<StudioChatRecord>(Json.encodeToString(inherited)).target,
        )
        assertEquals(target, newStudioHelperRecord("explicit", at, request, routedParent).target)
    }

    @Test
    fun `unknown parent model refuses default fallback but parentless helper may use host default`() {
        assertFailsWith<IllegalStateException> {
            newStudioHelperRecord("helper", at, request.copy(target = null), parent)
        }
        val detached = newStudioHelperRecord("detached", at, request.copy(parent = null, target = null), null)
        assertNull(detached.target)
    }

    @Test
    fun `old chat without helper metadata stays ordinary`() {
        val old = Json.decodeFromString<StudioChatRecord>(
            """{"id":"old","title":"Old","updatedAt":"2026-09-28T00:00:00Z"}""",
        )
        assertNull(old.helper)
    }
}
