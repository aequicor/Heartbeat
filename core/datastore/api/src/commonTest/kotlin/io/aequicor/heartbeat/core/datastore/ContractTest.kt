package io.aequicor.heartbeat.core.datastore

import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class ContractTest {

    @Test
    fun `storage names are safe file names`() {
        KeyValueSpec("settings")
        KeyValueSpec("chat_cache2")
        listOf("", "Settings", "1st", "../x", "a/b", "a.b", "a-b", "x".repeat(65)).forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { KeyValueSpec(name) }
            assertFailsWith<IllegalArgumentException>(name) { DatabaseSpec(name, { error("unused") }) }
        }
    }

    @Test
    fun `key names reject the reserved prefix and separators`() {
        stringKey("user.theme-mode_2")
        listOf("", "__hb.exp.x", "a/b", "a b", "x".repeat(129)).forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { stringKey(name) }
        }
    }

    @Test
    fun `keys are equal by name and type`() {
        assertEquals(stringKey("a"), stringKey("a"))
        assertNotEquals<StoreKey<*>>(stringKey("a"), intKey("a"))
        val list = ListSerializer(String.serializer())
        assertEquals(jsonKey("a", list), jsonKey("a", list))
    }

    @Test
    fun `event names are validated`() {
        DataEvent("auth.signed_out")
        listOf("", "Auth", "a b", "a/b").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { DataEvent(name) }
        }
    }

    @Test
    fun `retention describes itself`() {
        val event = DataEvent("auth.signed_out")
        assertTrue(Retention.Permanent.isPermanent)
        assertEquals("permanent", Retention.Permanent.toString())
        assertEquals("expires in 1h", Retention.expiring(Expiry.After(1.hours)).toString())
        assertEquals("until event auth.signed_out", Retention.untilEvent(event).toString())
        assertEquals(
            "expires at next 03:00 or until event auth.signed_out",
            Retention(Expiry.Daily(LocalTime(3, 0)), event).toString(),
        )
        assertFailsWith<IllegalArgumentException> { Expiry.After(Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { Expiry.After(Duration.INFINITE) }
    }
}
