package io.aequicor.heartbeat.feature.autocomplete.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant

class DesktopProjectFileIndexTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val workspace = WorkspaceRef("project")

    @Test
    fun `the walk lists files, skips build trees and ranks names first`() = runTest {
        val root = temporary.newFolder("files").toPath()
        write(root, "README.md", "hello")
        write(root, "src/common/Main.kt", "code")
        write(root, "build/generated/Big.class", "ignored")
        write(root, ".git/config", "ignored")

        val index = index(root)
        val found = index.search(workspace, "read", 10)

        assertEquals(listOf("README.md"), found.map { it.relativePath })
        val main = index.search(workspace, "Main", 10)
        assertEquals(listOf("src/common/Main.kt"), main.map { it.relativePath })
        val all = index.search(workspace, "", 100)
        assertEquals(listOf("README.md", "src/common/Main.kt"), all.map { it.relativePath }.sorted())
    }

    @Test
    fun `an unknown workspace resolves to no files`() = runTest {
        val root = temporary.newFolder("files").toPath()
        write(root, "README.md", "hello")
        assertEquals(emptyList(), index(root).search(WorkspaceRef("other"), "read", 10))
        assertEquals(emptyList(), index(root).search(null, "read", 10))
    }

    @Test
    fun `directories at the depth limit are not offered as files`() {
        val root = temporary.newFolder("files-depth").toPath()
        write(root, "nested/hidden.md", "file")
        assertEquals(emptyList(), walkProjectFiles(root, EmptyCoroutineContext, depthLimit = 1))
    }

    @Test
    fun `directory visits consume the scan budget`() {
        val root = temporary.newFolder("files-budget").toPath()
        write(root, "nested/readme.md", "file")
        assertEquals(emptyList(), walkProjectFiles(root, EmptyCoroutineContext, entryLimit = 2))
        assertEquals(
            listOf("nested/readme.md"),
            walkProjectFiles(root, EmptyCoroutineContext, entryLimit = 3).map { it.relativePath },
        )
    }

    @Test
    fun `cancellation during a scan propagates without finishing the tree`() {
        val root = temporary.newFolder("files-cancel").toPath()
        write(root, "readme.md", "file")
        val job = Job()
        var checks = 0
        val context = object : CoroutineContext by job {
            override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
                if (++checks == 3) job.cancel()
                return job[key]
            }
        }
        assertFailsWith<CancellationException> { walkProjectFiles(root, context) }
    }

    @Test
    fun `a symlink to an external file is never offered`() {
        val root = temporary.newFolder("files-link").toPath()
        val external = temporary.newFile("external.md").toPath()
        try {
            Files.createSymbolicLink(root.resolve("readme.md"), external)
        } catch (e: FileSystemException) {
            // Windows requires developer mode or a symlink privilege; explicitly skip when the host denies it.
            Log.tag("ProjectFileIndexTest").w(e) { "Symbolic links unavailable in this test environment" }
            assumeNoException(e)
        }
        assertEquals(emptyList(), walkProjectFiles(root, EmptyCoroutineContext))
    }

    private fun index(root: Path) = DesktopProjectFileIndex(
        object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe(): Flow<List<LocalWorkspace>> = flowOf(emptyList())
            override suspend fun register(directory: String): LocalWorkspace = throw UnsupportedOperationException()
            override suspend fun resolve(ref: WorkspaceRef): String? =
                ref.takeIf { it == workspace }?.let { root.toString() }
        },
        TestDispatchers,
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
        },
    )

    private object TestDispatchers : io.aequicor.heartbeat.core.common.DispatcherProvider {
        override val main = Dispatchers.Default
        override val default = Dispatchers.Default
        override val io = Dispatchers.Default
    }

    private fun write(root: Path, relative: String, content: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
