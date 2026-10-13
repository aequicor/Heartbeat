package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.host

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostArgumentsTest {
    private val socket = Path.of(System.getProperty("java.io.tmpdir")).resolve("bridge.sock").toAbsolutePath()
    private val origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/"

    @Test
    fun `socket and origin are parsed and the Windows parent window is ignored`() {
        assertEquals(HostArguments(socket, origin), HostArguments.parse(listOf(socket.toString(), origin)))
        assertEquals(
            HostArguments(socket, origin),
            HostArguments.parse(listOf(socket.toString(), origin, "--parent-window=1234")),
        )
    }

    @Test
    fun `unexpected arguments are rejected`() {
        assertFailsWith<IllegalArgumentException> { HostArguments.parse(emptyList()) }
        assertFailsWith<IllegalArgumentException> { HostArguments.parse(listOf("relative.sock", origin)) }
        assertFailsWith<IllegalArgumentException> { HostArguments.parse(listOf(socket.toString())) }
        assertFailsWith<IllegalArgumentException> {
            HostArguments.parse(listOf(socket.toString(), "chrome-extension://short/"))
        }
        assertFailsWith<IllegalArgumentException> {
            HostArguments.parse(listOf(socket.toString(), origin, origin))
        }
        assertFailsWith<IllegalArgumentException> {
            HostArguments.parse(listOf(socket.toString(), "https://example.com/"))
        }
    }
}
