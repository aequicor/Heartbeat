package io.aequicor.heartbeat.core.datastore.impl

import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.Expiry
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.booleanKey
import io.aequicor.heartbeat.core.datastore.doubleKey
import io.aequicor.heartbeat.core.datastore.floatKey
import io.aequicor.heartbeat.core.datastore.intKey
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.datastore.longKey
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.datastore.stringSetKey
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class KeyValueStoreTest {

    @Serializable
    data class Draft(val text: String, val lines: Int)

    private val settings = KeyValueSpec("settings")
    private val signedOut = DataEvent("auth.signed_out")
    private val alice = StorageOwner.Profile(ProfileId("alice"))

    private fun storageTest(block: suspend TestScope.(StorageTestEnv) -> Unit) = runTest {
        val env = StorageTestEnv(this)
        try {
            block(env)
            assertEquals(emptyList(), env.errors)
        } finally {
            withContext(NonCancellable) { env.dispose() }
        }
    }

    @Test
    fun `reads back every value type`() = storageTest { env ->
        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(settings)

        store.set(stringKey("s"), "text")
        store.set(intKey("i"), 1)
        store.set(longKey("l"), 2L)
        store.set(booleanKey("b"), true)
        store.set(doubleKey("d"), 0.5)
        store.set(floatKey("f"), 1.5f)
        store.set(stringSetKey("set"), setOf("a", "b"))
        store.set(jsonKey("json", Draft.serializer()), Draft("hi", 2))

        assertEquals("text", store.get(stringKey("s")))
        assertEquals(1, store.get(intKey("i")))
        assertEquals(2L, store.get(longKey("l")))
        assertEquals(true, store.get(booleanKey("b")))
        assertEquals(0.5, store.get(doubleKey("d")))
        assertEquals(1.5f, store.get(floatKey("f")))
        assertEquals(setOf("a", "b"), store.get(stringSetKey("set")))
        assertEquals(Draft("hi", 2), store.get(jsonKey("json", Draft.serializer())))

        store.remove(stringKey("s"))
        assertNull(store.get(stringKey("s")))
        store.clear()
        assertNull(store.get(intKey("i")))
    }

    @Test
    fun `an undecodable json value reads as absent`() = storageTest { env ->
        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(settings)
        store.set(stringKey("json"), "{not json")

        assertNull(store.get(jsonKey("json", Draft.serializer())))
        assertEquals(1, env.logged(LogLevel.WARNING, DS_LOG_TAG).size)
    }

    @Test
    fun `one store per name and none after the owner closed`() = storageTest { env ->
        val profile = env.newScope("profile", env.app)
        val stores = env.registry().attach(alice, profile)

        assertSame(stores.keyValue(settings), stores.keyValue(KeyValueSpec("settings")))
        profile.close()
        assertFailsWith<IllegalStateException> { stores.keyValue(settings) }
    }

    @Test
    fun `an expiring record disappears at its deadline and is deleted by the timer`() = storageTest { env ->
        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(settings) as LoggingKeyValueStore
        val key = stringKey("draft")
        val observed = mutableListOf<String?>()
        backgroundScope.launch(env.dispatcher) { store.observe(key).take(3).toList(observed) }

        store.set(key, "v1", Retention.expiring(Expiry.After(1.hours)))
        runCurrent()
        assertEquals(1.hours.inWholeMilliseconds + START.toEpochMilliseconds(), store.nextDeadline().first())

        advanceTimeBy(1.hours - 1.minutes)
        assertEquals("v1", store.get(key))
        advanceTimeBy(2.minutes)
        runCurrent()

        assertNull(store.get(key))
        assertNull(store.nextDeadline().first(), "the timer deleted the record and its retention")
        assertEquals(listOf(null, "v1", null), observed)
        assertEquals(1, env.logged(LogLevel.INFO, DS_LOG_TAG).count { "purged 1 expired records" in it })
    }

    @Test
    fun `permanent write clears the previous retention`() = storageTest { env ->
        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(settings) as LoggingKeyValueStore
        val key = stringKey("k")
        store.set(key, "v1", Retention.expiring(Expiry.After(1.hours)))
        store.set(key, "v2")

        advanceTimeBy(2.hours)
        assertEquals("v2", store.get(key))
        assertNull(store.nextDeadline().first())
    }

    @Test
    fun `a record expiring in the past is never visible`() = storageTest { env ->
        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(settings)
        store.set(stringKey("k"), "v", Retention.expiring(Expiry.At(START)))

        assertNull(store.get(stringKey("k")))
    }

    @Test
    fun `daily expiry is the next occurrence of the local time`() = storageTest { env ->
        val now = START.toEpochMilliseconds() // 10:00 UTC
        val hour = 1.hours.inWholeMilliseconds

        assertEquals(now + 2 * hour, env.retentionClock.deadline(Expiry.Daily(LocalTime(12, 0)), now))
        assertEquals(now + 17 * hour, env.retentionClock.deadline(Expiry.Daily(LocalTime(3, 0)), now))
        assertEquals(now + 24 * hour, env.retentionClock.deadline(Expiry.Daily(LocalTime(10, 0)), now))
    }

    @Test
    fun `firing an event deletes its records written before it`() = storageTest { env ->
        val stores = env.registry().attach(StorageOwner.App, env.app)
        val store = stores.keyValue(settings)
        store.set(stringKey("bound"), "v", Retention.untilEvent(signedOut))
        store.set(stringKey("other"), "v", Retention.untilEvent(DataEvent("other")))
        store.set(stringKey("plain"), "v")

        advanceTimeBy(1.minutes)
        stores.fire(signedOut)
        advanceTimeBy(1.minutes)
        store.set(stringKey("after"), "v", Retention.untilEvent(signedOut))

        assertNull(store.get(stringKey("bound")))
        assertEquals("v", store.get(stringKey("other")))
        assertEquals("v", store.get(stringKey("plain")))
        assertEquals("v", store.get(stringKey("after")))
    }

    @Test
    fun `an app event reaches closed profile storages when they reopen`() = storageTest { env ->
        val registry = env.registry()
        val app = registry.attach(StorageOwner.App, env.app)
        val firstSession = env.newScope("profile", env.app)
        registry.attach(alice, firstSession).keyValue(settings)
            .set(stringKey("k"), "v", Retention.untilEvent(signedOut))
        firstSession.close()

        advanceTimeBy(1.minutes)
        app.fire(signedOut)

        val secondSession = env.newScope("profile", env.app)
        assertNull(registry.attach(alice, secondSession).keyValue(settings).get(stringKey("k")))
    }

    @Test
    fun `a profile event does not touch the app or other profiles`() = storageTest { env ->
        val registry = env.registry()
        val app = registry.attach(StorageOwner.App, env.app).keyValue(settings)
        val alice = registry.attach(alice, env.newScope("alice", env.app))
        val bobOwner = StorageOwner.Profile(ProfileId("bob"))
        val bob = registry.attach(bobOwner, env.newScope("bob", env.app)).keyValue(settings)
        val bound = Retention.untilEvent(signedOut)
        listOf(app, alice.keyValue(settings), bob).forEach { it.set(stringKey("k"), "v", bound) }

        advanceTimeBy(1.minutes)
        alice.fire(signedOut)

        assertEquals("v", app.get(stringKey("k")))
        assertEquals("v", bob.get(stringKey("k")))
        assertNull(alice.keyValue(settings).get(stringKey("k")))
    }

    @Test
    fun `profile data survives a profile switch and a process restart`() = storageTest { env ->
        val first = env.newScope("profile", env.app)
        env.registry().attach(alice, first).keyValue(settings).set(stringKey("k"), "v")
        first.close()
        env.restartProcess()

        val store = env.registry().attach(alice, env.newScope("profile", env.app)).keyValue(settings)
        assertEquals("v", store.get(stringKey("k")))
        assertTrue(FileSystem.SYSTEM.exists(env.layout.keyValueFile(alice, "settings")))
    }

    @Test
    fun `wiping a profile deletes its files and only when its storages are closed`() = storageTest { env ->
        val registry = env.registry()
        val session = env.newScope("profile", env.app)
        registry.attach(alice, session).keyValue(settings).set(stringKey("k"), "v")
        registry.attach(StorageOwner.App, env.app).keyValue(settings).set(stringKey("k"), "v")

        assertFailsWith<IllegalStateException> { registry.wipeProfile(alice.id) }
        session.close()
        registry.wipeProfile(alice.id)

        assertFalse(FileSystem.SYSTEM.exists(env.layout.profileDir(alice.id)))
        assertTrue(FileSystem.SYSTEM.exists(env.layout.keyValueFile(StorageOwner.App, "settings")))
        val reopened = registry.attach(alice, env.newScope("profile", env.app)).keyValue(settings)
        assertNull(reopened.get(stringKey("k")))
    }

    @Test
    fun `a corrupted file is replaced with empty data and logged`() = storageTest { env ->
        val file = env.layout.keyValueFile(StorageOwner.App, "settings")
        FileSystem.SYSTEM.createDirectories(file.parent!!)
        // field 1, length-delimited, 127 bytes announced — the file ends right after the length
        FileSystem.SYSTEM.write(file) { write(byteArrayOf(0x0A, 0x7F)) }

        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(settings)
        assertNull(store.get(stringKey("k")))
        store.set(stringKey("k"), "v")
        assertEquals("v", store.get(stringKey("k")))
        assertEquals(1, env.logged(LogLevel.ERROR, DS_LOG_TAG).count { "corrupted" in it })
    }

    @Test
    fun `values are logged only for specs that allow it`() = storageTest { env ->
        val stores = env.registry().attach(StorageOwner.App, env.app)
        stores.keyValue(KeyValueSpec("private_data")).set(stringKey("note"), "SECRET-MARKER")
        stores.keyValue(KeyValueSpec("ui", areValuesLogged = true)).set(stringKey("theme"), "dark")

        assertTrue(env.logs.none { "SECRET-MARKER" in it })
        assertTrue(env.logs.any { "set note (permanent)" in it })
        assertTrue(env.logs.any { "INFO DS app/kv ui: set theme: absent -> dark (permanent)" in it })
    }

    @Test
    fun `wiping a profile leaves a profile whose id starts the same`() = storageTest { env ->
        val registry = env.registry()
        val aliceSession = env.newScope("alice", env.app)
        registry.attach(alice, aliceSession).keyValue(settings).set(stringKey("k"), "alice")
        aliceSession.close()
        val alice2 = registry.attach(StorageOwner.Profile(ProfileId("alice2")), env.newScope("alice2", env.app))
        alice2.keyValue(settings).set(stringKey("k"), "alice2")

        registry.wipeProfile(alice.id)

        assertEquals("alice2", alice2.keyValue(settings).get(stringKey("k")))
        alice2.keyValue(settings).set(stringKey("k"), "still writable")
        assertEquals("still writable", alice2.keyValue(settings).get(stringKey("k")))
    }

    @Test
    fun `a different spec with the same name is rejected`() = storageTest { env ->
        val stores = env.registry().attach(StorageOwner.App, env.app)
        stores.keyValue(KeyValueSpec("shared", areValuesLogged = true))

        assertFailsWith<IllegalStateException> { stores.keyValue(KeyValueSpec("shared")) }
    }

    @Test
    fun `a key redeclared with another type reads as absent`() = storageTest { env ->
        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(settings)
        store.set(intKey("count"), 3)

        assertNull(store.get(stringKey("count")))
        assertNull(store.get(jsonKey("count", Draft.serializer())))
        assertEquals(2, env.logged(LogLevel.WARNING, DS_LOG_TAG).count { "treated as absent" in it })
    }

    @Test
    fun `a corrupted event journal is kept aside and not overwritten silently`() = storageTest { env ->
        val stores = env.registry().attach(StorageOwner.App, env.app)
        val journal = env.layout.journalFile(StorageOwner.App)
        FileSystem.SYSTEM.createDirectories(journal.parent!!)
        FileSystem.SYSTEM.write(journal) { writeUtf8("{broken") }

        stores.fire(signedOut)

        assertTrue(FileSystem.SYSTEM.exists(journal.parent!! / "events.json.corrupt"))
        assertEquals(1, env.logged(LogLevel.ERROR, DS_LOG_TAG).count { "kept as" in it })
    }
}
