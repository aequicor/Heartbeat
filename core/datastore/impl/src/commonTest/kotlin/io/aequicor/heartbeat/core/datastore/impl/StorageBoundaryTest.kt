package io.aequicor.heartbeat.core.datastore.impl

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StorageBoundaryTest {

    @Test
    fun `an empty profile id is rejected before resolving the storage root`() {
        val layout = StorageLayout { error("the storage root must not be resolved") }

        assertFailsWith<IllegalArgumentException> { layout.profileDir(ProfileId("")) }
    }

    @Test
    fun `valid profiles keep their existing filenames`() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "heartbeat-layout"
        val layout = StorageLayout { root.toString() }
        val owner = StorageOwner.Profile(ProfileId("alice"))
        val directory = root / "storage" / "profiles" / "616c696365"

        assertEquals(directory, layout.profileDir(owner.id))
        assertEquals(directory / "kv" / "settings.preferences_pb", layout.keyValueFile(owner, "settings"))
        assertEquals(directory / "db" / "messages.db", layout.databaseFile(owner, "messages"))
        assertEquals(directory / "events.json", layout.journalFile(owner))
    }

    @Test
    fun `rejecting an empty profile wipe preserves open and closed profiles`() = runTest {
        val env = StorageTestEnv(this)
        try {
            val registry = env.registry()
            val settings = KeyValueSpec("settings")
            val key = stringKey("value")
            val alice = StorageOwner.Profile(ProfileId("alice"))
            val bob = StorageOwner.Profile(ProfileId("bob"))
            val aliceScope = env.newScope("alice", env.app)
            val aliceStore = registry.attach(alice, aliceScope).keyValue(settings)
            aliceStore.set(key, "alice")
            val bobScope = env.newScope("bob", env.app)
            registry.attach(bob, bobScope).keyValue(settings).set(key, "bob")
            bobScope.close()

            assertFailsWith<IllegalArgumentException> { registry.wipeProfile(ProfileId("")) }

            assertTrue(FileSystem.SYSTEM.exists(env.layout.keyValueFile(alice, settings.name)))
            assertTrue(FileSystem.SYSTEM.exists(env.layout.keyValueFile(bob, settings.name)))
            assertEquals("alice", aliceStore.get(key))
            aliceStore.set(key, "still writable")
            env.restartProcess()

            val reopened = env.registry()
            val aliceAgain = reopened.attach(alice, env.newScope("alice", env.app)).keyValue(settings)
            val bobAgain = reopened.attach(bob, env.newScope("bob", env.app)).keyValue(settings)
            assertEquals("still writable", aliceAgain.get(key))
            assertEquals("bob", bobAgain.get(key))
            assertEquals(emptyList(), env.errors)
        } finally {
            withContext(NonCancellable) { env.dispose() }
        }
    }
}
