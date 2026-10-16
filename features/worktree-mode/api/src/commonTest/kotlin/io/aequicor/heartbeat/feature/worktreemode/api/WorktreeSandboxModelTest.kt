package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreeSandboxModelTest {
    @Test
    fun `journal entries written before sandbox mode keep worktree isolation and exclusive caches`() {
        val task = Json.decodeFromString<WorktreeTask>(
            """{"chatId":"chat","project":{"value":"project"},"buildPlan":{"system":"gradle",""" +
                """"commands":[{"id":"check","executable":"./gradlew"}],""" +
                """"caches":[{"id":"gradle","directory":"cache"}]}}""",
        )
        assertFalse(task.isSandbox)
        assertEquals(WorktreeBuildKind.Compile, task.buildPlan?.commands?.single()?.kind)
        assertFalse(task.buildPlan?.caches?.single()?.isConcurrent ?: true)
    }

    @Test
    fun `sandbox flag with build kind and concurrent cache survive the journal round trip`() {
        val task = WorktreeTask(
            "chat",
            WorkspaceRef("project"),
            isSandbox = true,
            buildPlan = WorktreeBuildPlan(
                "gradle",
                listOf(WorktreeBuildCommand("test", "./gradlew", kind = WorktreeBuildKind.Test)),
                listOf(WorktreeBuildCache("gradle", "cache", isConcurrent = true)),
            ),
        )
        val encoded = Json.encodeToString(task)
        assertTrue("\"sandbox\":true" in encoded && "\"concurrent\":true" in encoded)
        assertEquals(task, Json.decodeFromString<WorktreeTask>(encoded))
    }
}
