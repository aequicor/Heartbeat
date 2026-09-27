package io.aequicor.heartbeat.core.secrets

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecretTest {
    @Test
    fun secretOwnsItsBufferAndErasesBorrowedCopies() {
        val input = "sensitive".toCharArray()
        val secret = Secret(input)
        input.fill('x')
        var borrowed = charArrayOf()
        secret.reveal {
            borrowed = it
            assertContentEquals("sensitive".toCharArray(), it)
        }
        assertTrue(borrowed.all { it == '\u0000' })
        assertEquals("Secret(***)", secret.toString())
        assertFailsWith<IllegalArgumentException> {
            secret.reveal {
                borrowed = it
                throw IllegalArgumentException("consumer failure")
            }
        }
        assertTrue(borrowed.all { it == '\u0000' })
        secret.close()
        secret.close()
        assertFailsWith<IllegalStateException> { secret.reveal { } }
        assertEquals("Secret(***)", secret.toString())
    }

    @Test
    fun identifiersRejectPathsAndBlankValues() {
        listOf("", "../key", "key/value", "a".repeat(129)).forEach {
            assertFailsWith<IllegalArgumentException> { SecretKey(it) }
            assertFailsWith<IllegalArgumentException> { SecretUsage("engine", it, "auth") }
        }
    }
}
