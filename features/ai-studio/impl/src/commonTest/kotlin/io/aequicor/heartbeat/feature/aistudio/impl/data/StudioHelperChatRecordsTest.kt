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
