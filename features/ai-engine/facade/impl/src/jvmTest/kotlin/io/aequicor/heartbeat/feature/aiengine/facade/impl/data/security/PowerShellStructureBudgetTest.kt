package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real process startup latency with fixture parser output; this does not claim to parse actual PowerShell. */
class PowerShellStructureBudgetTest {
    private val directory = Files.createTempDirectory("powershell-budget-")
    private val io = Executors.newCachedThreadPool().asCoroutineDispatcher()

    @AfterTest
    fun cleanUp() {
        io.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a parser that exceeds a short startup allowance can finish within its bounded budget`() = runTest {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val executable = parser()
        val insufficient = withContext(io) { PowerShellStructure(executable, io, timeoutMillis = 50).parse("pwd") }
        assertTrue(assertIs<ShellParseResult.Unknown>(insufficient).reason.contains("budget"))
        val sufficient = withContext(io) { PowerShellStructure(executable, io, timeoutMillis = 2_000).parse("pwd") }
        val parsed = assertIs<ShellParseResult.Parsed>(sufficient)
        assertEquals(listOf("pwd"), parsed.statements.single().commands.single().words)
    }

    private fun parser() = directory.resolve("parser").also { path ->
        // The delay is the process-startup condition under test, not synchronization between test coroutines.
        Files.writeString(
            path,
            """
                #!/bin/sh
                /bin/cat > /dev/null
                /bin/sleep 0.4
                printf '%s' '{"statements":[{"commands":[{"words":["pwd"],"redirects":[]}]}]}'
            """.trimIndent(),
        )
        check(path.toFile().setExecutable(true))
    }
}
