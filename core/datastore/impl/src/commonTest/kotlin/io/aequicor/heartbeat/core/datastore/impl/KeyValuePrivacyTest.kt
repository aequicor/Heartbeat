package io.aequicor.heartbeat.core.datastore.impl

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyValuePrivacyTest {

    private fun privacyTest(block: suspend TestScope.(StorageTestEnv, MutableList<Throwable>) -> Unit) = runTest {
        val env = StorageTestEnv(this)
        val errors = mutableListOf<Throwable>()
        val sink = LogSink { level, tag, error, message ->
            env.logs += "$level $tag $message ${error?.stackTraceToString().orEmpty()}"
            if (error != null) errors += error
        }
        Log.init(isDebug = false, sinks = listOf(sink))
        try {
            block(env, errors)
            assertEquals(emptyList(), env.errors)
            assertTrue(env.logs.none { PRIVATE_MARKER in it })
            val logged = errors.single()
            assertNull(logged.cause)
            assertTrue(logged.suppressedExceptions.isEmpty())
        } finally {
            withContext(NonCancellable) { env.dispose() }
        }
    }

    @Test
    fun `a JSON decoding failure does not log private input`() = privacyTest { env, errors ->
        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(KeyValueSpec("private_data"))
        store.set(stringKey("draft"), "\"$PRIVATE_MARKER\"")

        assertNull(store.get(jsonKey("draft", Int.serializer())))
        assertTrue(errors.single().message.orEmpty().contains("JsonDecodingException"))
    }

    @Test
    fun `a custom serializer failure does not log its message or cause`() = privacyTest { env, errors ->
        val store = env.registry().attach(StorageOwner.App, env.app).keyValue(KeyValueSpec("private_data"))
        val key = jsonKey("draft", RejectingSerializer)
        store.set(key, PRIVATE_MARKER)

        assertNull(store.get(key))
        assertTrue(errors.single().message.orEmpty().contains("IllegalArgumentException"))
    }

    private object RejectingSerializer : KSerializer<String> {
        override val descriptor = PrimitiveSerialDescriptor("PrivateValue", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

        override fun deserialize(decoder: Decoder): String {
            val value = decoder.decodeString()
            throw IllegalArgumentException("Cannot read $value", IllegalStateException("Nested $value"))
        }
    }

    private companion object {
        const val PRIVATE_MARKER = "PRIVATE-MARKER"
    }
}
