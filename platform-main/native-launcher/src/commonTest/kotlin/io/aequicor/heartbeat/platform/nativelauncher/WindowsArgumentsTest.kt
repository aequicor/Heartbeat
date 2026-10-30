package io.aequicor.heartbeat.platform.nativelauncher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsArgumentsTest {
    @Test
    fun spacesAndEmptyArgumentsKeepTheirBoundaries() {
        assertEquals(
            "\"C:\\Program Files\\Heartbeat.exe\" \"a b\" \"\" \"ключ\"",
            windowsCommandLine("C:\\Program Files\\Heartbeat.exe", listOf("a b", "", "ключ")),
        )
    }

    @Test
    fun trailingBackslashIsDoubledBeforeClosingQuote() {
        assertEquals("\"hb.exe\" \"C:\\folder\\\\\"", windowsCommandLine("hb.exe", listOf("C:\\folder\\")))
    }

    @Test
    fun nulAndQuotesCannotChangeExecutableIdentity() {
        assertFailsWith<IllegalArgumentException> { windowsCommandLine("hb\".exe", emptyList()) }
        assertFailsWith<IllegalArgumentException> { windowsCommandLine("hb.exe", listOf("a\u0000b")) }
    }
}
