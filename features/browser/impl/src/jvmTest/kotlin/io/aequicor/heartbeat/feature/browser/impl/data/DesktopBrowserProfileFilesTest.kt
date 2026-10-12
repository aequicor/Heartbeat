package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserNativeTestStores
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBrowserProfileFilesTest {
    @Test
    fun `fresh registry wipes unopened persisted browser data and preserves another profile`() = runTest {
        Fixture().use { fixture ->
            fixture.files().create()
            Files.writeString(fixture.browser.resolve("Cookies"), "private")
            val profiles = DesktopBrowserProfiles(
                BrowserTestScope(backgroundScope),
                dispatchers(StandardTestDispatcher(testScheduler)),
                lazy { BrowserNativeTestStores(fixture.application) },
            )
            val neighbor = profiles.files(ProfileId("bob"))
            neighbor.create()
            val sentinel = Files.writeString(neighbor.directory.toPath().resolve("Cookies"), "keep")
            assertEquals(profiles.rootDirectory, neighbor.directory.parentFile)
            profiles.wipeProfile(ProfileId("alice"))
            assertFalse(Files.exists(fixture.browser))
            assertEquals("keep", Files.readString(sentinel))
        }
    }

    @Test
    fun `deletion removes only the exact browser slot and is repeatable`() = runTest {
        Fixture().use { fixture ->
            val files = fixture.files()
            files.create()
            Files.writeString(fixture.browser.resolve("Cookies"), "private")
            val sibling = Files.writeString(fixture.browser.parent.resolve("attachment"), "keep")
            files.delete(dispatchers(StandardTestDispatcher(testScheduler)))
            files.delete(dispatchers(StandardTestDispatcher(testScheduler)))
            assertFalse(Files.exists(fixture.browser))
            assertEquals("keep", Files.readString(sibling))
        }
    }

    @Test
    fun `nested symlink or Windows junction is removed without deleting its target`() = runTest {
        Fixture().use { fixture ->
            val files = fixture.files()
            files.create()
            val sentinel = Files.writeString(fixture.outside.resolve("keep"), "outside")
            fixture.link(fixture.browser.resolve("redirect"), fixture.outside)
            files.delete(dispatchers(StandardTestDispatcher(testScheduler)))
            assertFalse(Files.exists(fixture.browser))
            assertEquals("outside", Files.readString(sentinel))
        }
    }

    @Test
    fun `redirected browser root is rejected for both deletion and initialization`() = runTest {
        Fixture().use { fixture ->
            Files.createDirectories(fixture.browser.parent)
            fixture.link(fixture.browser, fixture.outside)
            val sentinel = Files.writeString(fixture.outside.resolve("keep"), "outside")
            val files = fixture.files()
            assertFailsWith<IOException> { files.create() }
            assertFailsWith<IOException> { files.delete(dispatchers(StandardTestDispatcher(testScheduler))) }
            assertEquals("outside", Files.readString(sentinel))
        }
    }

    @Test
    fun `redirected files ancestor is rejected before traversing the target`() = runTest {
        Fixture().use { fixture ->
            Files.createDirectories(fixture.root.parent.parent)
            val outsideBrowser = Files.createDirectories(fixture.outside.resolve("browser_profiles/616c696365"))
            val sentinel = Files.writeString(outsideBrowser.resolve("keep"), "outside")
            fixture.link(fixture.root.parent, fixture.outside)
            assertFailsWith<IOException> {
                fixture.files().delete(dispatchers(StandardTestDispatcher(testScheduler)))
            }
            assertEquals("outside", Files.readString(sentinel))
        }
    }

    @Test
    fun `arbitrary parent and path traversal are rejected before filesystem mutation`() {
        Fixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                BrowserProfileFiles(fixture.root.toFile(), fixture.root.toFile())
            }
            assertFailsWith<IllegalArgumentException> {
                BrowserProfileFiles(fixture.outside.toFile(), fixture.root.toFile())
            }
            assertFailsWith<IllegalArgumentException> {
                BrowserProfileFiles(fixture.browser.resolve("../616c696365").toFile(), fixture.root.toFile())
            }
            assertTrue(Files.isDirectory(fixture.outside))
        }
    }

    private fun dispatchers(dispatcher: CoroutineDispatcher): DispatcherProvider = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }

    private class Fixture : AutoCloseable {
        val application: Path = Files.createTempDirectory("heartbeat-browser-storage")
        val root: Path = application.resolve("storage/app/files/browser_profiles")
        val outside: Path = Files.createTempDirectory("heartbeat-browser-neighbor")
        val browser: Path = root.resolve("616c696365")
        private val links = mutableListOf<Path>()

        fun files(): BrowserProfileFiles = BrowserProfileFiles(browser.toFile(), root.toFile())

        fun link(path: Path, target: Path) {
            if (System.getProperty("os.name").startsWith("Windows")) {
                val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", path.toString(), target.toString())
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                check(process.waitFor() == 0) { output }
            } else {
                Files.createSymbolicLink(path, target)
            }
            links.add(path)
        }

        override fun close() {
            links.forEach { Files.deleteIfExists(it) }
            listOf(application, outside).forEach { directory ->
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
            }
        }
    }
}
