package io.aequicor.heartbeat.core.di.impl

import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopeSavedStateImplTest {

    private val records = mutableListOf<LogRecord>()

    @BeforeTest
    fun setUp() = Log.init(
        isDebug = false,
        sinks = listOf(LogSink { level, _, error, message -> records += LogRecord(level, error, message) }),
    )

    @AfterTest
    fun tearDown() = Log.init(isDebug = false)

    private fun state(restored: SavedBundle? = null) = ScopeSavedStateImpl("test", restored)

    @Test
    fun registered_value_is_restored_once() {
        val before = state()
        before.register("count", Int.serializer()) { 42 }

        val after = state(before.snapshot())

        assertEquals(42, after.consume("count", Int.serializer()))
        assertNull(after.consume("count", Int.serializer()))
    }

    @Test
    fun unconsumed_values_survive_the_next_snapshot() {
        val before = state()
        before.register("late", String.serializer()) { "value" }

        // nobody consumed "late" in this process — it must not be lost on the next save
        val intermediate = state(before.snapshot())
        val after = state(intermediate.snapshot())

        assertEquals("value", after.consume("late", String.serializer()))
    }

    @Test
    fun unreadable_value_is_dropped() {
        val restored = state(SavedBundle(mapOf("count" to "\"not a number\"")))

        assertNull(restored.consume("count", Int.serializer()))
    }

    @Test
    fun key_can_be_registered_only_once() {
        val state = state()
        state.register("k", Int.serializer()) { 1 }

        assertFailsWith<IllegalStateException> { state.register("k", Int.serializer()) { 2 } }
    }

    @Test
    fun incompatible_saved_draft_is_dropped_without_logging_its_contents() {
        // The old version stored a string; the current version expects a list of paragraphs.
        val restored = state(SavedBundle(mapOf("draft" to "\"$PRIVATE_DRAFT\"")))

        assertNull(restored.consume("draft", ListSerializer(String.serializer())))
        assertEquals(emptyMap(), restored.snapshot().entries)
        assertPrivateDataNotLogged(LogLevel.WARNING, "JsonDecodingException")
    }

    @Test
    fun custom_decoder_validation_does_not_expose_saved_data_in_logs() {
        val restored = state(SavedBundle(mapOf("draft" to "\"$PRIVATE_DRAFT\"")))
        val serializer = object : KSerializer<String> by String.serializer() {
            override fun deserialize(decoder: Decoder): String {
                val value = decoder.decodeString()
                require(value.length <= 10) { "Draft is too long: $value" }
                return value
            }
        }

        assertNull(restored.consume("draft", serializer))
        assertPrivateDataNotLogged(LogLevel.WARNING, "IllegalArgumentException")
    }

    @Test
    fun failing_supplier_does_not_expose_saved_data_in_logs() {
        val state = state()
        state.register("draft", String.serializer()) { throw IllegalStateException(PRIVATE_DRAFT) }

        assertEquals(emptyMap(), state.snapshot().entries)
        assertPrivateDataNotLogged(LogLevel.ERROR, "IllegalStateException")
    }

    private fun assertPrivateDataNotLogged(level: LogLevel, originalType: String) {
        val record = records.single()
        assertEquals(level, record.level)
        val error = assertNotNull(record.error)
        assertTrue(originalType in error.message.orEmpty())
        assertFalse(PRIVATE_DRAFT in record.message + error.stackTraceToString())
        assertNull(error.cause)
    }

    private data class LogRecord(val level: LogLevel, val error: Throwable?, val message: String)

    private companion object {
        const val PRIVATE_DRAFT = "Private draft with personal information"
    }
}
