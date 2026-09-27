package io.aequicor.heartbeat.core.datastore.impl

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyValueLifecycleTest {

    private val settings = KeyValueSpec("settings")
    private val alice = StorageOwner.Profile(ProfileId("alice"))
    private val key = stringKey("draft")

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
    fun `closed handles reject reads writes and late observations after reopening`() = storageTest { env ->
        val registry = env.registry()
        val firstSession = env.newScope("alice", env.app)
        val stores = registry.attach(alice, firstSession)
        val old = stores.keyValue(settings)
        old.set(key, "saved")
        val pendingObservation = old.observe(key)
        firstSession.close()

        val reopened = registry.attach(alice, env.newScope("alice", env.app)).keyValue(settings)
        assertEquals("saved", reopened.get(key))
        assertFailsWith<IllegalStateException> { old.get(key) }
        assertFailsWith<IllegalStateException> { old.set(key, "stale") }
        assertFailsWith<IllegalStateException> { old.remove(key) }
        assertFailsWith<IllegalStateException> { old.clear() }
        assertFailsWith<IllegalStateException> { old.observe(key).first() }
        assertFailsWith<IllegalStateException> { pendingObservation.first() }
        assertFailsWith<IllegalStateException> { stores.fire(DataEvent("signed_out")) }
        assertEquals("saved", reopened.get(key))
        reopened.set(key, "new session")
        assertEquals("new session", reopened.get(key))
        assertTrue(coroutineContext.job.isActive, "closing the owner must not cancel a completed call's caller")
    }

    @Test
    fun `closing the owner cancels observations collected outside its scope`() = storageTest { env ->
        val registry = env.registry()
        val session = env.newScope("alice", env.app)
        val old = registry.attach(alice, session).keyValue(settings)
        old.set(key, "saved")
        val observed = mutableListOf<String?>()
        val observation = backgroundScope.launch(env.dispatcher) { old.observe(key).toList(observed) }
        runCurrent()
        assertEquals(listOf<String?>("saved"), observed)

        session.close()
        runCurrent()
        assertTrue(observation.isCancelled)
        val reopened = registry.attach(alice, env.newScope("alice", env.app)).keyValue(settings)
        reopened.set(key, "new session")
        runCurrent()

        assertEquals(listOf<String?>("saved"), observed)
        assertEquals("new session", reopened.get(key))
        assertTrue(env.app.job.isActive)
    }

    @Test
    fun `closing the owner cancels a pending write from an external caller`() = storageTest { env ->
        val session = env.newScope("alice", env.app)
        val preferences = PausingPreferences()
        val store = LoggingKeyValueStore(
            spec = settings,
            label = "alice/settings",
            dataStore = preferences,
            clock = env.retentionClock,
            journal = { emptyMap() },
            scope = session,
        )
        store.set(key, "saved")
        preferences.pauseNextWrite = true
        val write = backgroundScope.async(env.dispatcher) { store.set(key, "stale") }
        runCurrent()
        assertTrue(preferences.writeStarted.isCompleted)

        session.close()
        preferences.resumeWrite.complete(Unit)
        runCurrent()

        assertTrue(write.isCancelled)
        assertEquals("saved", preferences.data.value[stringPreferencesKey(key.name)])
        assertTrue(env.app.job.isActive)
    }

    private class PausingPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        val writeStarted = CompletableDeferred<Unit>()
        val resumeWrite = CompletableDeferred<Unit>()
        var pauseNextWrite = false

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (pauseNextWrite) {
                pauseNextWrite = false
                writeStarted.complete(Unit)
                resumeWrite.await()
            }
            return transform(data.value).also { data.value = it }
        }
    }
}
