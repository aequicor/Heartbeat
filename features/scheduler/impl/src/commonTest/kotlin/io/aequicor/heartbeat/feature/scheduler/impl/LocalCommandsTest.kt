package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandRequest
import io.aequicor.heartbeat.feature.scheduler.impl.data.CommandOutcome
import io.aequicor.heartbeat.feature.scheduler.impl.data.CommandRunner
import io.aequicor.heartbeat.feature.scheduler.impl.data.CommandShell
import io.aequicor.heartbeat.feature.scheduler.impl.data.RunnerLocalCommands
import io.aequicor.heartbeat.feature.scheduler.impl.data.quotedCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LocalCommandsTest {
    @Test
    fun `posix quoting keeps every word literal`() {
        val command = quotedCommand(CommandShell.Posix, "./gradlew", listOf("test", "it's \$HOME; rm -rf *", ""))

        assertEquals("'./gradlew' 'test' 'it'\\''s \$HOME; rm -rf *' ''", command)
    }

    @Test
    fun `powershell quoting doubles single quotes and keeps the exit code`() {
        val command = quotedCommand(CommandShell.PowerShell, ".\\gradlew.bat", listOf("it's", "a;b"))

        assertEquals("& '.\\gradlew.bat' 'it''s' 'a;b'; exit \$LASTEXITCODE", command)
    }

    @Test
    fun `powershell doubles typographic single quotes`() {
        val breakout = "\u2019;Start-Process calc;#"
        val command = quotedCommand(CommandShell.PowerShell, "git", listOf(breakout, "\u2018\u201A\u201B"))

        val doubled = "'\u2019\u2019;Start-Process calc;#' '\u2018\u2018\u201A\u201A\u201B\u201B'"
        assertEquals("& 'git' $doubled; exit \$LASTEXITCODE", command)
    }

    @Test
    fun `powershell refuses words it cannot pass unchanged`() {
        val refused = listOf(
            "say \"hi\"", "", "x&calc", "a|b", "<in", ">out", "^", "%PATH%", "!x!", "(a)", "a\nb", "a\rb", "a b\\",
        )
        refused.forEach { word ->
            assertNull(quotedCommand(CommandShell.PowerShell, ".\\gradlew.bat", listOf(word)), word)
        }
        assertNull(quotedCommand(CommandShell.PowerShell, "x&calc", emptyList()))
        assertEquals(
            "& 'git' 'C:\\dir\\'; exit \$LASTEXITCODE",
            quotedCommand(CommandShell.PowerShell, "git", listOf("C:\\dir\\")),
        )
    }

    @Test
    fun `runs the quoted program in the project directory`() = runTest {
        val runner = ShellCommands(CommandOutcome(3, "out"))
        val commands = RunnerLocalCommands(runner, Projects())

        val outcome = commands.run(LocalCommandRequest(PROJECT, "git", listOf("status"), 10.seconds))

        assertEquals(LocalCommandOutcome.Exited(3, "out"), outcome)
        assertEquals(listOf(Triple("/work/project", "'git' 'status'", 10.seconds)), runner.runs)
    }

    @Test
    fun `a timeout keeps the output`() = runTest {
        val commands = RunnerLocalCommands(ShellCommands(CommandOutcome(null, "partial")), Projects())

        assertEquals(
            LocalCommandOutcome.TimedOut("partial"),
            commands.run(LocalCommandRequest(PROJECT, "sleep", listOf("100"))),
        )
    }

    @Test
    fun `an unknown project runs nothing`() = runTest {
        val runner = ShellCommands(CommandOutcome(0, ""))
        val commands = RunnerLocalCommands(runner, Projects())

        assertEquals(
            LocalCommandOutcome.NoProject,
            commands.run(LocalCommandRequest(WorkspaceRef("gone"), "git")),
        )
        assertTrue(runner.runs.isEmpty())
    }

    @Test
    fun `arguments the shell cannot pass run nothing`() = runTest {
        val runner = ShellCommands(CommandOutcome(0, ""), CommandShell.PowerShell)
        val commands = RunnerLocalCommands(runner, Projects())

        assertEquals(
            LocalCommandOutcome.Invalid,
            commands.run(LocalCommandRequest(PROJECT, "git", listOf("\""))),
        )
        assertTrue(runner.runs.isEmpty())
    }

    @Test
    fun `platforms without processes are unsupported`() = runTest {
        val commands = RunnerLocalCommands(RecordingCommands(isAvailable = false), Projects())

        assertFalse(commands.isAvailable)
        assertEquals(LocalCommandOutcome.Unsupported, commands.run(LocalCommandRequest(PROJECT, "git")))
    }

    @Test
    fun `requests are bounded`() {
        assertFailsWith<IllegalArgumentException> { LocalCommandRequest(PROJECT, " ") }
        assertFailsWith<IllegalArgumentException> { LocalCommandRequest(PROJECT, "git", timeout = 31.minutes) }
        assertFailsWith<IllegalArgumentException> { LocalCommandRequest(PROJECT, "git", listOf("a\u0000b")) }
    }
}

private class ShellCommands(
    private val outcome: CommandOutcome,
    override val shell: CommandShell = CommandShell.Posix,
) : CommandRunner {
    val runs = mutableListOf<Triple<String, String, Duration>>()
    override val isAvailable: Boolean = true

    override suspend fun run(directory: String, command: String, timeout: Duration): CommandOutcome {
        runs += Triple(directory, command, timeout)
        return outcome
    }
}
