package io.aequicor.heartbeat.core.di.impl

import io.aequicor.heartbeat.core.di.SavedBundle
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScopeSavedStateImplTest {

    private fun state(restored: SavedBundle? = null) = ScopeSavedStateImpl("test", restored, Json)

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
}
