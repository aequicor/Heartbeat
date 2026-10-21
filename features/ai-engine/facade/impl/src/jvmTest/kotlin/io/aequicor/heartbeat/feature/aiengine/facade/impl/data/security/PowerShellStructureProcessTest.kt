package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeNotNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Actual PowerShell AST and JVM process transport, independently of policy and model decisions. */
class PowerShellStructureProcessTest {
    private val io = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val directory = Files.createTempDirectory("powershell-parser-process-")

    @AfterTest
    fun cleanUp() {
        io.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `actual parser reads the complete frame without executing the input`() = runTest {
        val executable = executable()
        val stages = ConcurrentLinkedQueue<PowerShellParserStage>()
        val parser = PowerShellStructure(checkNotNull(executable), io, onStage = { stages.add(it) })
        val listing = withContext(io) {
            parser.parse("Get-ChildItem -Directory | Select-Object -ExpandProperty Name")
        }
        val parsed = assertIs<ShellParseResult.Parsed>(listing, "Parser transport stages: ${stages.toList()}")
        assertEquals(PowerShellParserStage.entries.toSet(), stages.toSet())
        assertEquals(2, parsed.statements.single().commands.size)
        assertEquals("Get-ChildItem", parsed.statements.single().commands.first().words.first())
        val marker = directory.resolve("must-not-exist.txt")
        withContext(io) { parser.parse("Set-Content -LiteralPath '$marker' -Value changed") }
        assertFalse(Files.exists(marker), "The proposed command must remain parser data")
    }

    @Test
    fun `complete Unicode frame returns an AST while the peer keeps stdin open`() = runTest {
        val executable = executable()
        withContext(io) {
            val process = startParser(executable)
            try {
                val path = "пример-😀\\\"\t\n\u0001"
                val command = "Get-ChildItem -LiteralPath '$path' -Directory"
                process.outputStream.write("${command.length}\n$command".toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
                // Deliberately keep the writer open: the old ReadToEnd transport cannot complete this request.
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Complete frames must not wait for stdin EOF")
                assertEquals(0, process.exitValue())
                val document = Json.parseToJsonElement(process.inputStream.readAllBytes().toString(Charsets.UTF_8))
                val words = document.jsonObject.getValue("statements").jsonArray.single().jsonObject
                    .getValue("commands").jsonArray.single().jsonObject.getValue("words").jsonArray
                assertEquals(path, words[2].jsonPrimitive.content)
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.outputStream.close()
                process.inputStream.close()
            }
        }
    }

    @Test
    fun `premature EOF cannot produce complete analysis`() = runTest {
        val executable = executable()
        withContext(io) {
            val process = startParser(executable)
            try {
                process.outputStream.use { it.write("40\npwd".toByteArray(Charsets.UTF_8)) }
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertTrue(process.exitValue() != 0)
                assertEquals("{\"unknown\":true}", process.inputStream.readAllBytes().toString(Charsets.UTF_8))
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.outputStream.close()
                process.inputStream.close()
            }
        }
    }

    private fun executable(): Path {
        val configured = System.getenv("HEARTBEAT_POWERSHELL_PARSER_EXECUTABLE")
        val executable = configured?.let(Path::of) ?: System.getenv("SystemRoot")?.let {
            Path.of(it, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        }
        assumeNotNull(executable)
        return checkNotNull(executable)
    }

    private fun startParser(executable: Path): Process {
        val script = checkNotNull(javaClass.getResourceAsStream("/security/powershell-structure.ps1"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return ProcessBuilder(
            executable.toString(),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)),
        ).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
            environment()["PSModulePath"] = executable.parent.resolve("Modules").toString()
        }.start()
    }
}
