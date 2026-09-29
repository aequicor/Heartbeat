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
            piExtensions(Path.of("runtime"), searchTools = true),
            "read,edit",
            isProject = false,
        )
        assertTrue("--no-extensions" in arguments)
        assertTrue("--no-approve" in arguments && "--no-skills" in arguments && "--no-context-files" in arguments)
        assertEquals(
            listOf(
                Path.of("runtime", "heartbeat-approval.ts").toString(),
                Path.of("runtime", "heartbeat-search.ts").toString(),
            ),
            arguments.windowed(2).filter { it.first() == "-e" }.map { it.last() },
        )
        assertEquals(
            listOf(Path.of("runtime", "heartbeat-approval.ts")),
            piExtensions(Path.of("runtime"), searchTools = false),
        )
        val extension = assertNotNull(javaClass.getResourceAsStream("/pi/heartbeat-search.ts"))
        extension.use { stream ->
            val source = stream.bufferedReader().readText()
            assertTrue("registerTool" in source)
            assertTrue("web_search" in source && "web_fetch" in source)
        }
    }

    @Test
    fun `project session loads project content but no executable resources`() {
        val arguments = piCommand(
            Path.of("pi"),
            "anthropic",
            Path.of("sessions"),
            piExtensions(Path.of("runtime"), searchTools = false),
            "read,edit",
            isProject = true,
        )
        // The folder the user opened grants project trust: context files and skills may load.
        assertTrue("--approve" in arguments)
        assertTrue("--no-approve" !in arguments)
        assertTrue("--no-skills" !in arguments)
        assertTrue("--no-context-files" !in arguments)
        // Project-supplied code and other executable or interactive resources stay disabled.
        assertTrue("--no-extensions" in arguments)
        assertTrue("--no-prompt-templates" in arguments)
        assertTrue("--no-themes" in arguments)
        assertEquals(
            listOf(Path.of("runtime", "heartbeat-approval.ts").toString()),
            arguments.windowed(2).filter { it.first() == "-e" }.map { it.last() },
        )
    }
}
