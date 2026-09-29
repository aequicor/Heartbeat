package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.workspace

import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.KoogTool
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.KoogToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoogFileToolsTest {
    private val parent: Path = Files.createTempDirectory("koog-tools")
    private val project: Path = parent.resolve("project").createDirectories()
    private val tools = koogFileTools(ProjectRoot(project), Dispatchers.IO).associateBy { it.descriptor.name }

    @AfterTest
    fun cleanUp() {
        parent.toFile().deleteRecursively()
    }

    @Test
    fun readReturnsNumberedLinesAndPagination() = runTest {
        project.resolve("a.txt").writeText("one\ntwo\nthree")
        val result = call("read_file", "path" to "a.txt", "offset" to 2, "limit" to 1)
        assertFalse(result.isFailed)
        assertEquals("2\ttwo\n… 1 more lines, continue with offset=3", result.text)
    }

    @Test
    fun pathsOutsideTheProjectAreRefused() = runTest {
        parent.resolve("secret.txt").writeText("secret")
        assertTrue(call("read_file", "path" to "../secret.txt").isFailed)
        assertTrue(call("read_file", "path" to parent.resolve("secret.txt").toString()).isFailed)
        assertTrue(call("write_file", "path" to "../escape.txt", "content" to "x").isFailed)
        assertFalse(Files.exists(parent.resolve("escape.txt")))
    }

    @Test
    fun writeCreatesParentsAndEditReplacesUniqueFragment() = runTest {
        assertFalse(call("write_file", "path" to "src/Main.kt", "content" to "fun a() = 1\nfun b() = 1").isFailed)
        val ambiguous = call("edit_file", "path" to "src/Main.kt", "old_string" to "= 1", "new_string" to "= 2")
        assertTrue(ambiguous.isFailed)
        val edited = call("edit_file", "path" to "src/Main.kt", "old_string" to "b() = 1", "new_string" to "b() = 2")
        assertFalse(edited.isFailed)
        assertEquals("fun a() = 1\nfun b() = 2", project.resolve("src/Main.kt").readText())
    }

    @Test
    fun globAndGrepSkipBuildOutput() = runTest {
        project.resolve("src").createDirectories().resolve("A.kt").writeText("val needle = 1")
        project.resolve("build").createDirectories().resolve("B.kt").writeText("val needle = 2")
        assertEquals("src/A.kt", call("glob", "pattern" to "**/*.kt").text)
        assertEquals("src/A.kt:1: val needle = 1", call("grep", "pattern" to "needle").text)
        assertEquals("build/\nsrc/", call("list_dir").text)
    }

    @Test
    fun invalidGlobIsReportedAsFailure() = runTest {
        val grep = call("grep", "pattern" to "needle", "glob" to "{a")
        assertTrue(grep.isFailed)
        assertTrue(grep.text.startsWith("Invalid glob"))
    }

    @Test
    fun onlyWritesAreMutating() {
        assertEquals(
            setOf("write_file", "edit_file"),
            tools.values.filter(KoogTool::isMutating).map { it.descriptor.name }.toSet(),
        )
    }

    private suspend fun call(name: String, vararg args: Pair<String, Any>): KoogToolResult =
        requireNotNull(tools[name]).run(
            JsonObject(
                args.associate { (key, value) ->
                    key to when (value) {
                        is Int -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    }
                },
            ),
        )
}
