package io.aequicor.heartbeat.feature.browser.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserNativeTestStores
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPersistentBrowserProfileTest {
    @Test
    fun `failed data clear can be retried and completes the pending deletion`() = runTest {
        withStorage { application, outside ->
            val profile = profile(application)
            val browser = application.resolve("storage/app/files/browser_profiles/616c696365")
            Files.createDirectories(browser.parent)
            // A redirected slot makes the deletion fail without touching the link target.
            link(browser, outside)
            assertFailsWith<IOException> { profile.clearData() }
            assertTrue(profile.isAvailable)
            assertTrue(Files.exists(browser.resolveSibling("616c696365.clearing")))
            // Until a clear succeeds, the native context stays closed.
            assertFailsWith<IllegalStateException> { profile.useContext { } }
            assertFailsWith<IllegalStateException> { profile.useAgentContext("session") { } }
            Files.delete(browser)
            Files.createDirectories(browser)
            Files.writeString(browser.resolve("Cookies"), "partially deleted")
            profile.clearData()
            assertTrue(profile.isAvailable)
            assertFalse(Files.exists(browser))
            assertFalse(Files.exists(browser.resolveSibling("616c696365.clearing")))
            assertTrue(Files.isDirectory(outside))
        }
    }

    @Test
    fun `discarding the storage of an agent session without one is harmless`() = runTest {
        withStorage { application, _ ->
            val profile = profile(application)
            profile.releaseAgentContext("session") { false }
            profile.releaseAgentContext("session") { true }
            assertTrue(profile.isAvailable)
        }
    }

    private fun TestScope.profile(application: Path): DesktopPersistentBrowserProfile {
        val dispatchers = dispatchers(StandardTestDispatcher(testScheduler))
        val app = BrowserTestScope(backgroundScope)
        val profiles = DesktopBrowserProfiles(app, dispatchers, lazy { BrowserNativeTestStores(application) })
        return DesktopPersistentBrowserProfile(
            dispatchers,
            BrowserTestScope(backgroundScope),
            app,
            ProfileStores(ProfileId("alice")),
            profiles,
        )
    }

    private suspend fun withStorage(block: suspend (application: Path, outside: Path) -> Unit) {
        val application = Files.createTempDirectory("heartbeat-browser-profile")
        val outside = Files.createTempDirectory("heartbeat-browser-outside")
        try {
            block(application, outside)
        } finally {
            listOf(application, outside).forEach { directory ->
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
            }
        }
    }

    private fun link(path: Path, target: Path) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", path.toString(), target.toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { output }
        } else {
            Files.createSymbolicLink(path, target)
        }
    }

    private fun dispatchers(dispatcher: CoroutineDispatcher): DispatcherProvider = object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    }

    private class ProfileStores(id: ProfileId) : DataStores {
        override val owner = StorageOwner.Profile(id)
        override fun filesDirectory(name: String): String = error("Profile files are not used")
        override fun keyValue(spec: KeyValueSpec): KeyValueStore = error("Profile key-value storage is not used")
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("Profile Room is not used")
        override suspend fun fire(event: DataEvent): Unit = error("Profile retention events are not used")
    }
}
