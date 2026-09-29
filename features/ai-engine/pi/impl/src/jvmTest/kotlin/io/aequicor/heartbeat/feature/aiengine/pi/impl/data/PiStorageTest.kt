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
    fun `transcript is found by its native session id only`() {
        val app = createTempDirectory("app")
        val storage = PiStorage(StorageRootOf(app), app.resolve("missing-legacy"))
        val sessions = sessionDirectory(createTempDirectory("profile"))
        Files.createDirectories(sessions)
        val id = "01a0eb5c-e7a1-7149-852f-2283519b0adb"
        Files.writeString(sessions.resolve("2026-09-29T04-12-09-505Z_$id.jsonl"), "{}")
        Files.writeString(sessions.resolve("2026-09-29T04-22-36-597Z_other.jsonl"), "{}")

        val expected = sessions.resolve("2026-09-29T04-12-09-505Z_$id.jsonl").toString()
        assertEquals(expected, storage.transcript(sessions, id))
        assertEquals(null, storage.transcript(sessions, "missing"))
        assertEquals(null, storage.transcript(sessions, "*"))
        assertEquals(null, storage.transcript(sessions.resolve("absent"), id))
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
