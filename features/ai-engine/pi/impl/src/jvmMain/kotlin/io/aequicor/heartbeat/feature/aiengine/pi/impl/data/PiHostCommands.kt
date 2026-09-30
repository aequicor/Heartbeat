package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

/**
 * Whether [call] runs a command that can terminate the application hosting this session.
 *
 * A desktop app started from the IDE is forked by the Gradle daemon, so `gradlew --stop` — the step this project
 * documents for the detekt rule cache — stops that daemon and the app goes down with it: at once, with no stack
 * trace, no crash report and no chance to finish the running turn. Process killers and power commands end the host
 * the same way. Trust never covers such a call; only the user decides, and the request tells what it costs.
 *
 * Recognition is by the word that starts a statement, so looking these words up (`git grep taskkill`,
 * `Select-String -Pattern Stop-Process`) is not a host termination. The word scan is a heuristic: it prevents an
 * unattended mistake, it is not a sandbox.
 */
internal fun terminatesHost(call: PiApprovalCall): Boolean =
    call.tool !in EditTools && statements(call.target).asSequence().any { terminatesHostStatement(it) }

/** Whether one statement kills processes or stops the Gradle daemons that own the host. */
private fun terminatesHostStatement(statement: String): Boolean {
    val words = statement.split(HostWhitespace).filter { it.isNotBlank() }
    val command = starts(words)?.commandName() ?: return false
    return command in HostKillers ||
        (command in GradleLaunchers && words.any { it.unquoted() in GradleStopFlags })
}

/**
 * Statements of [command]: split at the separators that are not inside a quoted argument, which may hold one
 * literally (`git grep "a|b"`). An unbalanced quote swallows the rest of the line; a command hidden that way still
 * reaches the user, because trust covers only what this scan recognises as safe.
 */
private fun statements(command: String): List<String> = buildList {
    var quote: Char? = null
    var start = 0
    command.forEachIndexed { index, char ->
        val open = quote
        when {
            open != null -> if (char == open) quote = null

            char in HostQuotes -> quote = char

            char in HostSeparators -> {
                add(command.substring(start, index))
                start = index + 1
            }
        }
    }
    add(command.substring(start))
}

/** The first word that runs something: not a flag, an assignment, a call operator or a launcher of another word. */
private fun starts(words: List<String>): String? = words.asSequence()
    .map { it.unquoted() }
    .firstOrNull { it.isNotEmpty() && !it.isIntroducer() }

/** Bare lower-case name of a command word: no directory and no executable extension. */
private fun String.commandName(): String {
    val file = substringAfterLast('/').substringAfterLast('\\')
    return (if (file.extension().lowercase() in ExecutableExtensions) file.substringBeforeLast('.') else file)
        .lowercase()
}

private fun String.extension(): String = substringAfterLast('.', missingDelimiterValue = "")
private fun String.unquoted(): String = trim { it in HostQuotes }

/** Flags, `NAME=value` assignments, `cmd` switches, call operators and wrappers of the command behind them. */
private fun String.isIntroducer(): Boolean =
    startsWith('-') || matches(CmdSwitch) || matches(HostAssignment) || isLauncher()

private fun String.isLauncher(): Boolean = lowercase() in CommandLaunchers || this in HostCallOperators

/** Command separators of `sh`, PowerShell and `cmd`; a redirection such as `2>&1` splits harmlessly. */
private val HostSeparators = setOf(';', '|', '&', '\r', '\n')
private val HostWhitespace = Regex("\\s+")
private val HostQuotes = setOf('"', '\'', '`')

/** Words that only call the command after them: `&` and `.` of PowerShell and `sh`. */
private val HostCallOperators = setOf("&", ".", "&&", "||")

/** A one- or two-letter `cmd` switch; a longer `/`-prefixed word is the absolute path of the command to run. */
private val CmdSwitch = Regex("/[A-Za-z]{1,2}")

/** `NAME=value` (sh) and `$env:NAME='value'` (PowerShell) set the environment instead of running a command. */
private val HostAssignment = Regex("^(\\\$env:)?[A-Za-z_][A-Za-z0-9_]*=.*")

/** Commands that end processes or the machine, whatever they name as their target. */
private val HostKillers = setOf(
    "halt",
    "kill",
    "killall",
    "pkill",
    "poweroff",
    "reboot",
    "restart-computer",
    "shutdown",
    "stop-computer",
    "stop-process",
    "taskkill",
)

/** Wrappers that run the command behind them; `cmd`, `powershell` and the shells take it as a quoted argument. */
private val CommandLaunchers = setOf(
    "bash",
    "cmd",
    "doas",
    "eval",
    "exec",
    "nohup",
    "powershell",
    "pwsh",
    "sh",
    "start",
    "sudo",
    "time",
    "wsl",
    "xargs",
    "zsh",
)

private val GradleLaunchers = setOf("gradle", "gradlew")
private val GradleStopFlags = setOf("--stop", "-stop")

/** Extensions a host shell runs directly; stripping them maps `gradlew.bat` and `taskkill.exe` to their name. */
private val ExecutableExtensions = setOf("bat", "bash", "cmd", "com", "exe", "ps1", "sh", "zsh")
