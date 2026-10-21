package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.feature.aiengine.facade.api.POWER_SHELL_UTF8_INITIALIZATION
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security.shellProcessArguments
import org.junit.Assume.assumeNotNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** Actual cmdlet pipeline with identical environment and source through both PowerShell CLI transports. */
class PowerShellPipelineTransportTest {
    private fun outputTail(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            output.write(buffer, 0, count)
            if (output.size() > 65_536) {
                val bytes = output.toByteArray()
                output.reset()
                output.write(bytes, bytes.size - 65_536, 65_536)
            }
        }
    }

    private fun pipelineSource(directory: Path): String {
        val literalDirectory = directory.toString().replace("'", "''")
        return POWER_SHELL_UTF8_INITIALIZATION +
            "\$ErrorActionPreference = 'Stop'\n" +
            "[Console]::Out.WriteLine('before-dotnet-listing')\n" +
            "[Console]::Out.WriteLine([System.IO.Directory]::GetDirectories('$literalDirectory').Length)\n" +
            "[Console]::Out.WriteLine('before-management-import')\n" +
            "Import-Module Microsoft.PowerShell.Management -ErrorAction Stop -Verbose\n" +
            "[Console]::Out.WriteLine('after-management-import')\n" +
            "[Console]::Out.WriteLine('before-listing')\n" +
            "\$items = Microsoft.PowerShell.Management\\Get-ChildItem " +
            "-LiteralPath '$literalDirectory' -Directory\n" +
            "[Console]::Out.WriteLine('before-utility-import')\n" +
            "Import-Module Microsoft.PowerShell.Utility -ErrorAction Stop -Verbose\n" +
            "[Console]::Out.WriteLine('before-projection')\n" +
            "\$items | Select-Object -ExpandProperty Name\n" +
            "[Console]::Out.WriteLine('after-projection')\n"
    }

    @Test
    fun `both native transports complete the controlled listing and projection`() {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val executable = System.getenv("HEARTBEAT_POWERSHELL_PARSER_EXECUTABLE")?.let(Path::of)
            ?: System.getenv("SystemRoot")?.let {
                Path.of(it, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
            }
        assumeNotNull(executable)
        val shell = checkNotNull(executable)
        val directory = Files.createTempDirectory("powershell-pipeline-")
        try {
            Files.createDirectory(directory.resolve("expected-child"))
            val source = pipelineSource(directory)
            val results = listOf(false, true).map { encoded ->
                val prefix = listOf(shell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive")
                val arguments = if (encoded) {
                    val encodedSource = Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_16LE))
                    prefix + listOf("-EncodedCommand", encodedSource)
                } else {
                    shellProcessArguments(prefix + listOf("-Command", source), isWindows = windows)
                }
                val builder = ProcessBuilder(arguments).directory(directory.toFile()).redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(File(if (windows) "NUL" else "/dev/null")))
                builder.environment().clear()
                builder.environment().putAll(controlledCodingEnvironment(windows))
                builder.environment()["PSModulePath"] = shell.parent.resolve("Modules").toString()
                val process = builder.start()
                val output = CompletableFuture.supplyAsync { outputTail(process.inputStream) }
                try {
                    val exited = process.waitFor(15, TimeUnit.SECONDS)
                    if (!exited) {
                        process.destroyForcibly()
                        process.waitFor(5, TimeUnit.SECONDS)
                    }
                    val text = output.get(2, TimeUnit.SECONDS).toString(Charsets.UTF_8)
                    val passed = exited && process.exitValue() == 0 &&
                        "expected-child" in text && "after-projection" in text
                    passed to "encoded=$encoded, exited=$exited, checkpoints/output=$text"
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                    process.inputStream.close()
                    output.cancel(true)
                }
            }
            assertTrue(results.all { it.first }, results.joinToString("\n") { it.second })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
