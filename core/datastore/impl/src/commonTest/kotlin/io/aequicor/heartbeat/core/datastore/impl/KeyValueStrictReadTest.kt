package io.aequicor.heartbeat.core.datastore.impl

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.intKey
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okio.FileSystem
import okio.IOException
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyValueStrictReadTest {
    private val settings = KeyValueSpec("strict_settings", readPolicy = KeyValueReadPolicy.Strict)
    private val alice = StorageOwner.Profile(ProfileId("alice"))
    private val key = stringKey("level")

    @Test
    fun `missing strict settings remain absent and valid values survive profile reopening`() = storageTest { env ->
        val registry = env.registry()
        val profile = env.newScope("alice", env.app)
        val store = registry.attach(alice, profile).keyValue(settings)
        assertNull(store.get(key))
        assertNull(store.observe(key).first())
        store.set(key, "Ask")
        profile.close()

        val reopened = registry.attach(alice, env.newScope("alice again", env.app)).keyValue(settings)
        assertEquals("Ask", reopened.get(key))
        assertFailsWith<IllegalStateException> { store.get(key) }
    }

    @Test
    fun `corrupt strict settings fail without rewriting through reads writes and profile reopening`() =
        storageTest { env ->
            env.registry().attach(alice, env.newScope("alice", env.app)).keyValue(settings).set(key, "Ask")
            env.restartProcess()
            val file = env.layout.keyValueFile(alice, settings.name)
            val corrupted = byteArrayOf(0x0A, 0x7F)
            FileSystem.SYSTEM.write(file) { write(corrupted) }
            val registry = env.registry()
            val profile = env.newScope("alice", env.app)
            val store = registry.attach(alice, profile).keyValue(settings)

            assertFailsWith<CorruptionException> { store.get(key) }
            assertFailsWith<CorruptionException> { store.observe(key).first() }
            assertFailsWith<CorruptionException> { store.set(key, "Trust") }
            runCurrent()
            assertContentEquals(corrupted, FileSystem.SYSTEM.read(file) { readByteArray() })
            assertTrue(env.errors.isEmpty())
            profile.close()

            val reopened = registry.attach(alice, env.newScope("alice again", env.app)).keyValue(settings)
            assertFailsWith<CorruptionException> { reopened.get(key) }
            assertContentEquals(corrupted, FileSystem.SYSTEM.read(file) { readByteArray() })
            assertFalse(env.logs.any { "replaced with empty data" in it })
        }

    @Test
    fun `strict retention initialization retries corruption and stops when the profile closes`() = storageTest { env ->
        val file = env.layout.keyValueFile(alice, settings.name)
        FileSystem.SYSTEM.createDirectories(file.parent!!)
        val corrupted = byteArrayOf(0x0A, 0x7F)
        FileSystem.SYSTEM.write(file) { write(corrupted) }
        val profile = env.newScope("alice", env.app)
        val store = env.registry().attach(alice, profile).keyValue(settings)
        assertFailsWith<CorruptionException> { store.get(key) }
        runCurrent()
        val initialFailures = env.retentionFailures()
        assertTrue(initialFailures > 0)

        advanceTimeBy(60_000L)
        runCurrent()
        assertTrue(env.retentionFailures() > initialFailures)
        assertContentEquals(corrupted, FileSystem.SYSTEM.read(file) { readByteArray() })
        profile.close()
        runCurrent()
        val failuresAtClose = env.retentionFailures()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(failuresAtClose, env.retentionFailures())
        assertTrue(env.app.job.isActive)
    }

    @Test
    fun `cached files cannot change read policy across owner lifetimes`() = storageTest { env ->
        val registry = env.registry()
        for (policy in KeyValueReadPolicy.entries) {
            val spec = KeyValueSpec("settings_${policy.name.lowercase()}", readPolicy = policy)
            val profile = env.newScope("alice", env.app)
            registry.attach(alice, profile).keyValue(spec).set(key, "Ask")
            profile.close()
            val reopened = registry.attach(alice, env.newScope("alice again", env.app))
            val opposite = KeyValueReadPolicy.entries.single { it != policy }

            assertFailsWith<IllegalStateException> { reopened.keyValue(spec.copy(readPolicy = opposite)) }
            assertEquals("Ask", reopened.keyValue(spec).get(key))
        }
    }

    @Test
    fun `strict reads reject incompatible values and preserve the original stored value`() = storageTest { env ->
        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(settings)
        store.set(intKey(key.name), 7)

        assertFailsWith<IllegalStateException> { store.get(key) }
        assertFailsWith<IllegalStateException> { store.observe(key).first() }
        assertEquals(7, store.get(intKey(key.name)))
    }

    @Test
    fun `strict decoding errors never expose stored values or serializer causes`() = storageTest { env ->
        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(settings)
        val privateValue = "private-payload-marker"
        store.set(key, "{broken:$privateValue")
        val malformed = assertFailsWith<SerializationException> { store.get(jsonKey(key.name, String.serializer())) }
        assertFalse(privateValue in malformed.stackTraceToString())
        store.set(key, "\"$privateValue\"")
        val failures: List<(String) -> Exception> = listOf(
            { IllegalArgumentException(it) },
            { IllegalStateException(it) },
        )
        for (failure in failures) {
            val serializerFailure = assertFailsWith<SerializationException> {
                store.observe(jsonKey(key.name, RejectingSerializer(failure))).first()
            }
            assertFalse(privateValue in serializerFailure.stackTraceToString())
        }
        assertFalse(env.logs.any { privateValue in it })
        assertEquals("\"$privateValue\"", store.get(key))
    }

    @Test
    fun `strict reads propagate IO while default recovery still returns absence`() = storageTest { env ->
        assertEquals(KeyValueReadPolicy.Recover, KeyValueSpec("default_settings").readPolicy)
        for (policy in KeyValueReadPolicy.entries) {
            val failure = IOException("read unavailable")
            val store = failingReadStore(env, policy, failure)
            if (policy == KeyValueReadPolicy.Strict) {
                assertEquals(failure.message, assertFailsWith<IOException> { store.get(key) }.message)
                assertEquals(failure.message, assertFailsWith<IOException> { store.observe(key).first() }.message)
            } else {
                assertNull(store.get(key))
                assertNull(store.observe(key).first())
            }
        }
    }

    @Test
    fun `neither read policy converts cancellation to absence`() = storageTest { env ->
        for (policy in KeyValueReadPolicy.entries) {
            val failure = CancellationException("cancelled read")
            val store = failingReadStore(env, policy, failure)
            assertFailsWith<CancellationException> { store.get(key) }
            assertFailsWith<CancellationException> { store.observe(key).first() }
        }
    }

    private fun failingReadStore(env: StorageTestEnv, policy: KeyValueReadPolicy, failure: Exception) =
        LoggingKeyValueStore(
            spec = settings.copy(readPolicy = policy),
            label = "strict read test",
            dataStore = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow { throw failure }

                // Preparation succeeds so the test exercises data collection rather than initial retention IO.
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    transform(emptyPreferences())
            },
            clock = env.retentionClock,
            journal = { emptyMap() },
            scope = env.app,
        )

    private fun StorageTestEnv.retentionFailures(): Int =
        logged(LogLevel.WARNING, DS_LOG_TAG).count { "retention timer failed" in it }

    private fun storageTest(block: suspend TestScope.(StorageTestEnv) -> Unit) = runTest {
        val env = StorageTestEnv(this)
        try {
            block(env)
            assertEquals(emptyList(), env.errors)
        } finally {
            withContext(NonCancellable) { env.dispose() }
        }
    }

    private class RejectingSerializer(private val failure: (String) -> Exception) : KSerializer<String> {
        override val descriptor = PrimitiveSerialDescriptor("RejectingSerializer", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

        override fun deserialize(decoder: Decoder): String = throw failure(decoder.decodeString())
    }
}
