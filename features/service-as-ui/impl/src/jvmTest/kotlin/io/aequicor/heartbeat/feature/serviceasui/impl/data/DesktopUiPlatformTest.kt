package io.aequicor.heartbeat.feature.serviceasui.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.desktopdialogs.DesktopFileDialogs
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatformResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopUiPlatformTest {
    @Test
    fun `saving writes only where the user picks and a closed dialog is a cancel`() = runTest {
        val folder = Files.createTempDirectory("service-ui").toFile()
        val dialogs = FakeDialogs(save = File(folder, "map.md").path)
        val platform = DesktopUiPlatform(TestDispatchers(StandardTestDispatcher(testScheduler)), dialogs, MacOs)

        assertEquals(UiPlatformResult.Done(), platform.save("# Map", "map.md", listOf("md")))
        assertEquals("# Map", File(folder, "map.md").readText())
        assertEquals(listOf<Pair<String?, List<String>>>("map.md" to listOf("md")), dialogs.saves)

        dialogs.save = null
        assertEquals(UiPlatformResult.Cancelled, platform.save("# Map", "map.md", listOf("md")))
        assertEquals(UiPlatformResult.Failed, platform.save("x".repeat(4 * 1024 * 1024 + 1), "big.txt", emptyList()))
        assertEquals(2, dialogs.saves.size)
        folder.deleteRecursively()
    }

    @Test
    fun `pickers return the chosen path and reveal needs an existing absolute path`() = runTest {
        val dialogs = FakeDialogs(save = null, files = listOf("/tmp/a.json", "/tmp/b.json"), folder = "/tmp")
        val platform = DesktopUiPlatform(TestDispatchers(StandardTestDispatcher(testScheduler)), dialogs, MacOs)

        assertEquals(UiPlatformResult.Done("/tmp/a.json"), platform.pick(isFolder = false, listOf("json")))
        assertEquals(listOf(listOf("json")), dialogs.picks)
        assertEquals(UiPlatformResult.Done("/tmp"), platform.pick(isFolder = true, emptyList()))
        dialogs.files = emptyList()
        assertEquals(UiPlatformResult.Cancelled, platform.pick(isFolder = false, emptyList()))
        dialogs.failure = IllegalStateException("backend")
        assertEquals(UiPlatformResult.Failed, platform.pick(isFolder = true, emptyList()))

        assertEquals(UiPlatformResult.Failed, platform.reveal("relative/file.txt"))
        assertEquals(UiPlatformResult.Failed, platform.reveal(File("/definitely/missing/file.txt").absolutePath))
    }

    private class FakeDialogs(var save: String?, var files: List<String> = emptyList(), var folder: String? = null) :
        DesktopFileDialogs {
        val saves = mutableListOf<Pair<String?, List<String>>>()
        val picks = mutableListOf<List<String>>()
        var failure: Exception? = null

        override suspend fun pickDirectory(title: String?): String? {
            failure?.let { throw it }
            return folder
        }

        override suspend fun pickFiles(title: String?, extensions: List<String>, allowMultiple: Boolean): List<String> {
            picks += extensions
            return files
        }

        override suspend fun pickSaveLocation(
            title: String?,
            suggestedName: String?,
            extensions: List<String>,
        ): String? {
            saves += suggestedName to extensions
            return save
        }
    }

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
    }

    private object MacOs : PlatformInfo {
        override val host: HostPlatform = HostPlatform.MacOs
    }
}
