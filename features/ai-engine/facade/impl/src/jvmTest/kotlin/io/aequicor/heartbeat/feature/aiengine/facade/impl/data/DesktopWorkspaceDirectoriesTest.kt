package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopWorkspaceDirectoriesTest {
    @Test
    fun `only existing readable absolute directories are accepted and canonicalized`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val directories = DesktopWorkspaceDirectories(object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        })
        val root = Files.createTempDirectory("heartbeat-local-project")
        try {
            val project = Files.createDirectory(root.resolve("Project"))
            val file = Files.createFile(root.resolve("file.txt"))
            assertEquals(
                WorkspaceDirectory(project.toRealPath().toString(), "Project"),
                directories.canonical(project.resolve("..").resolve("Project").toString()),
            )
            assertNull(directories.canonical("relative"))
            assertNull(directories.canonical(root.resolve("missing").toString()))
            assertNull(directories.canonical(file.toString()))
            assertNull(directories.canonical("\u0000"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
