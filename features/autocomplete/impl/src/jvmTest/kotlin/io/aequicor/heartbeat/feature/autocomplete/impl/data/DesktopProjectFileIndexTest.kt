package io.aequicor.heartbeat.feature.autocomplete.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class DesktopProjectFileIndexTest {
    private val workspace = WorkspaceRef("project")

    @Test
    fun `the walk lists files, skips build trees and ranks names first`() = runTest {
        val root = Files.createTempDirectory("files")
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
        val root = Files.createTempDirectory("files")
        write(root, "README.md", "hello")
        assertEquals(emptyList(), index(root).search(WorkspaceRef("other"), "read", 10))
        assertEquals(emptyList(), index(root).search(null, "read", 10))
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
