package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodingBoundaryTest {
    @Test
    fun `path normalization allows project files and blocks traversal absolute paths and metadata`() {
        val parent = Files.createTempDirectory("heartbeat-project-boundary")
        try {
            val directory = Files.createDirectory(parent.resolve("project"))
            val project = ProjectRoot(directory)
            assertEquals(directory.resolve("file.txt"), project.resolve("sub/../file.txt"))
            assertEquals(directory.resolve("nested/new.txt"), project.resolve("nested/new.txt"))
            assertFailsWith<OutsideProjectException> { project.resolve("../outside.txt") }
            assertFailsWith<OutsideProjectException> { project.resolve(parent.resolve("outside.txt").toString()) }
            assertFailsWith<OutsideProjectException> { project.resolve(".git/config") }
            assertFailsWith<OutsideProjectException> { project.resolve(".GIT/config") }
            assertFailsWith<OutsideProjectException> { project.resolve("\u0000") }
        } finally {
            removeTree(parent)
        }
    }

    @Test
    fun `coding writes and edits cannot reach an outside file or git metadata`() = runTest {
        val parent = Files.createTempDirectory("heartbeat-coding-boundary")
        try {
            val directory = Files.createDirectory(parent.resolve("project"))
            val outside = Files.writeString(parent.resolve("outside.txt"), "secret")
            val git = Files.createDirectory(directory.resolve(".git"))
            val config = Files.writeString(git.resolve("config"), "metadata")
            val tools = codingFileTools(ProjectRoot(directory), StandardTestDispatcher(testScheduler))
            val write = tools.single { it.descriptor.name == "write_file" }
            val edit = tools.single { it.descriptor.name == "edit_file" }
            assertTrue(
                write.run(
                    buildJsonObject {
                        put("path", "../outside.txt")
                        put("content", "changed")
                    },
                ).isError,
            )
            assertTrue(
                edit.run(
                    buildJsonObject {
                        put("path", ".git/config")
                        put("old_string", "metadata")
                        put("new_string", "changed")
                    },
                ).isError,
            )
            assertEquals("secret", Files.readString(outside))
            assertEquals("metadata", Files.readString(config))
            assertFalse(
                write.run(
                    buildJsonObject {
                        put("path", "src/file.txt")
                        put("content", "inside")
                    },
                ).isError,
            )
            assertEquals("inside", Files.readString(directory.resolve("src/file.txt")))
        } finally {
            removeTree(parent)
        }
    }

    @Test
    fun `existing symlink prefix and git alias are checked before creating a missing tail`() {
        val parent = Files.createTempDirectory("heartbeat-project-link-boundary")
        try {
            val directory = Files.createDirectory(parent.resolve("project"))
            val outside = Files.createDirectory(parent.resolve("outside"))
            val git = Files.createDirectory(directory.resolve(".git"))
            // Windows needs Developer Mode or link privilege; other boundary cases run on every filesystem.
            if (!createLink(directory.resolve("escape"), outside)) return
            assertTrue(createLink(directory.resolve("metadata"), git))
            val project = ProjectRoot(directory)
            assertFailsWith<OutsideProjectException> { project.resolve("escape/not-created/new.txt") }
            assertFailsWith<OutsideProjectException> { project.resolve("metadata/not-created/config") }
        } finally {
            removeTree(parent)
        }
    }
}

private fun createLink(link: Path, target: Path): Boolean = try {
    Files.createSymbolicLink(link, target)
    true
} catch (e: IOException) {
    Log.tag("CodingBoundaryTest").w(e) { "Symbolic links unavailable in this test environment" }
    false
} catch (e: UnsupportedOperationException) {
    Log.tag("CodingBoundaryTest").w(e) { "Symbolic links unsupported in this test environment" }
    false
}

/** Files.walk does not follow links, so cleaning a test fixture never traverses its outside targets. */
private fun removeTree(root: Path) {
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
