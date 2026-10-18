package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualCodingToolsTest {
    @Test
    fun `directory tools accept dot as the virtual project root`() = runTest {
        val tools = virtualCodingTools(TextWorkspace("source"))
        for (name in listOf("list_dir", "glob", "grep")) {
            val result = tools.single { it.descriptor.name == name }.run(
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive("./"),
                        "pattern" to JsonPrimitive(if (name == "grep") "source" else "*"),
                    ),
                ),
            )
            assertFalse(result.isError)
            assertEquals(if (name == "grep") "source.txt:1: source" else "source.txt", result.text)
        }
    }

    @Test
    fun `large exact fragment is replaced without materializing sliding windows`() = runTest {
        val fragment = "x".repeat(20_000)
        val workspace = TextWorkspace("a".repeat(30_000) + fragment + "b".repeat(30_000))
        assertFalse(edit(workspace, fragment).isError)
        assertEquals("a".repeat(30_000) + "replacement" + "b".repeat(30_000), workspace.content)
    }

    @Test
    fun `overlapping matches require replace all and rejection preserves content`() = runTest {
        val workspace = TextWorkspace("aaa")
        assertTrue(edit(workspace, "aa").isError)
        assertEquals("aaa", workspace.content)
        assertFalse(edit(workspace, "aa", replaceAll = true).isError)
        assertEquals("replacementa", workspace.content)
    }

    @Test
    fun `missing and empty fragments leave virtual files unchanged`() = runTest {
        val workspace = TextWorkspace("source")
        assertTrue(edit(workspace, "missing").isError)
        assertTrue(edit(workspace, "").isError)
        assertEquals("source", workspace.content)
    }

    private suspend fun edit(workspace: TextWorkspace, fragment: String, replaceAll: Boolean = false) =
        virtualCodingTools(workspace).single { it.descriptor.name == "edit_file" }.run(
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive("source.txt"),
                    "old_string" to JsonPrimitive(fragment),
                    "new_string" to JsonPrimitive("replacement"),
                    "replace_all" to JsonPrimitive(replaceAll),
                ),
            ),
        )

    private class TextWorkspace(var content: String) : VirtualWorkspace {
        override val ref = WorkspaceRef("virtual-test")
        override val processDirectory = "unused"
        override suspend fun revision(): Long = 0
        override suspend fun paths(): List<String> = listOf("source.txt")
        override suspend fun read(path: String): String = content
        override suspend fun change(expectedRevision: Long, changes: List<VirtualFileChange>): Long {
            content = requireNotNull(changes.single().content)
            return 1
        }

        override suspend fun move(expectedRevision: Long, path: String, destination: String): Long =
            error("Not used by edit tests")
    }
}
