package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PiSearchRegistrationTest {
    @Test
    fun `hosted registry loads independently of search and skips duplicate native approvals`() {
        val names = listOf("configure_build", "run_build")
        assertTrue(piTools(searchTools = false, hosted = names).endsWith(",configure_build,run_build"))
        assertTrue(Path.of("runtime", "heartbeat-tools.ts") in piExtensions(Path.of("runtime"), false, true))
        val extension = assertNotNull(javaClass.getResourceAsStream("/pi/heartbeat-tools.ts"))
        extension.use { assertTrue("Type.Unsafe(spec.inputSchema)" in it.bufferedReader().readText()) }
        val approvals = assertNotNull(javaClass.getResourceAsStream("/pi/heartbeat-approval.ts"))
        approvals.use { assertTrue("HOSTED_TOOLS.has(event.toolName)" in it.bufferedReader().readText()) }
    }

    @Test
    fun `search toggle controls the explicit native tools allowlist`() {
        val shell = if (System.getProperty("os.name").startsWith("Windows")) "powershell" else "bash"
        val base = listOf("read", shell, "edit", "write")
        assertEquals(base, piTools(searchTools = false).split(","))
        assertEquals(base + listOf("web_search", "web_fetch"), piTools(searchTools = true).split(","))
    }

    @Test
    fun `isolated Pi process loads bundled search tools beside approval gate`() {
        val arguments = piCommand(
            Path.of("pi"),
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
            arguments.asSequence().windowed(2).filter { it.first() == "-e" }.map { it.last() }.toList(),
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
            arguments.asSequence().windowed(2).filter { it.first() == "-e" }.map { it.last() }.toList(),
        )
    }

    @Test
    fun `the command line leaves model selection to the session over RPC`() {
        val arguments = piCommand(
            Path.of("pi"),
            Path.of("sessions"),
            piExtensions(Path.of("runtime"), searchTools = false),
            "read,edit",
        )
        // Pi rejects --provider without --model, and a --model pattern reads ':' in an id as a thinking level.
        assertTrue("--provider" !in arguments)
        assertTrue("--model" !in arguments)
        assertEquals(listOf("pi", "--mode", "rpc"), arguments.take(3))
    }
}
