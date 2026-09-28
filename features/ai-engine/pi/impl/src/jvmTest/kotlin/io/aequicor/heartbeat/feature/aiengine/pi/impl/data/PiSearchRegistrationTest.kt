package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PiSearchRegistrationTest {
    @Test
    fun `isolated Pi process loads bundled search tools beside approval gate`() {
        val arguments = piCommand(
            Path.of("pi"),
            "anthropic",
            Path.of("sessions"),
            listOf(Path.of("runtime", "heartbeat-approval.ts"), Path.of("runtime", "heartbeat-search.ts")),
            "read,edit",
        )
        assertTrue("--no-extensions" in arguments)
        assertEquals(
            listOf(
                Path.of("runtime", "heartbeat-approval.ts").toString(),
                Path.of("runtime", "heartbeat-search.ts").toString(),
            ),
            arguments.windowed(2).filter { it.first() == "-e" }.map { it.last() },
        )
        val extension = assertNotNull(javaClass.getResourceAsStream("/pi/heartbeat-search.ts"))
        extension.use { stream ->
            val source = stream.bufferedReader().readText()
            assertTrue("registerTool" in source)
            assertTrue("web_search" in source && "web_fetch" in source)
        }
    }
}
