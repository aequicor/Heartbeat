package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiStorageTest {
    @Test
    fun `profile directories live under the application data directory`() {
        val app = createTempDirectory("app")
        val storage = PiStorage(StorageRootOf(app), app.resolve("missing-legacy"))

        val profile = storage.profileRoot("profile-1")

        assertEquals(app.resolve("engines/pi").toAbsolutePath().normalize(), profile.parent)
        assertFalse(profile.fileName.toString().contains("profile-1"))
    }

    @Test
    fun `legacy home directory data is removed on first access`() {
        val app = createTempDirectory("app")
        val home = createTempDirectory("home")
        val legacy = Files.createDirectories(home.resolve(".heartbeat/pi/abc/sessions"))
        Files.writeString(legacy.resolve("s.jsonl"), "{}")

        PiStorage(StorageRootOf(app), home.resolve(".heartbeat/pi")).profileRoot("profile-1")

        assertFalse(Files.exists(home.resolve(".heartbeat")))
    }

    @Test
    fun `wipe removes only the profile directory`() = runTest {
        val app = createTempDirectory("app")
        val storage = PiStorage(StorageRootOf(app), app.resolve("missing-legacy"))
        val wiped = Files.createDirectories(storage.profileRoot("a").resolve("sessions"))
        val kept = Files.createDirectories(storage.profileRoot("b").resolve("sessions"))

        PiStorageCleaner(TestDispatchers, storage).wipeProfile(ProfileId("a"))

        assertFalse(Files.exists(wiped.parent))
        assertTrue(Files.exists(kept))
    }
}

private class StorageRootOf(private val dir: Path) : StorageRoot {
    override fun path(): String = dir.toString()
}

private object TestDispatchers : DispatcherProvider {
    override val main = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
}
