package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexArgumentsTest {
    @Test
    fun `legacy Windows process arguments retain embedded quotes and trailing slashes`() {
        val raw = listOf("codex.exe", "model=\"open ai\"", "path=C:\\cfg\\", "value=\\\"quoted\"")
        assertEquals(
            listOf("codex.exe", "\"model=\\\"open ai\\\"\"", "\"path=C:\\cfg\\\\\"", "\"value=\\\\\\\"quoted\\\"\""),
            codexProcessArguments(raw, isWindows = true, allowAmbiguousCommands = true),
        )
        assertEquals(raw, codexProcessArguments(raw, isWindows = true, allowAmbiguousCommands = false))
        assertEquals(raw, codexProcessArguments(raw, isWindows = false))
    }

    @Test
    fun `a native Windows child receives TOML configuration as literal argv`() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val directory = Files.createTempDirectory("codex-argv")
        try {
            val program = directory.resolve("Arguments.java")
            Files.writeString(program, ARGUMENTS_PROGRAM)
            val arguments = listOf("model_provider=\"openai\"", "values=[\"a b\",\"c\"]", "path=C:\\with spaces\\")
            val java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
            val process = ProcessBuilder(codexProcessArguments(listOf(java, program.toString()) + arguments)).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                val received = process.inputStream.bufferedReader().use { reader ->
                    reader.readLines().map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
                }
                assertEquals(arguments, received)
            } finally {
                process.destroyForcibly()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private const val ARGUMENTS_PROGRAM = """
import java.util.Base64;
class Arguments {
    public static void main(String[] args) {
        for (String arg : args) {
            System.out.println(Base64.getEncoder().encodeToString(arg.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
"""
