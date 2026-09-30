package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

/**
 * Whether [call] runs a command that can terminate the application hosting this session.
 *
 * A desktop app started from the IDE is forked by the Gradle daemon, so `gradlew --stop` — the step this project
 * documents for the detekt rule cache — stops that daemon and the app goes down with it: at once, with no stack
 * trace, no crash report and no chance to finish the running turn. Process killers and power commands end the host
 * the same way. Turn trust never approves a recognised host termination; only the user decides, and the request tells
 * what it costs.
 *
 * Recognition follows the executable at the start of a statement and the command payload of common shell
 * wrappers, including their paths and executable extensions. Quoted arguments remain intact, so looking these
 * words up (`git grep taskkill`, `Select-String -Pattern Stop-Process`) is not a host termination. This is a heuristic
 * against unattended mistakes, not a shell interpreter or a sandbox: unknown forms remain subject to turn trust.
 * A chain exceeding the wrapper scan limit also asks the user, so nested wrappers cannot exhaust the call stack.
 */
internal fun terminatesHost(call: PiApprovalCall): Boolean =
    call.tool !in EditTools && terminatesHostCommand(call.target)

private fun terminatesHostCommand(command: String, wrappersLeft: Int = HOST_WRAPPER_LIMIT): Boolean =
    HostCommandScan().scan(command).any { terminatesHostWords(it, wrappersLeft) }

/** Examines executables and wrappers only; the other words of an ordinary command are data. */
private fun terminatesHostWords(words: List<HostWord>, wrappersLeft: Int): Boolean {
    val index = words.indexOfFirst { !it.value.isIntroducer() }
    if (index < 0) return false
    val command = words[index].value.commandName()
    val arguments = words.drop(index + 1)
    return when {
        command in HostKillers -> true

        command in GradleLaunchers -> arguments.any { it.value in GradleStopFlags }

        command == "eval" ->
            wrappersLeft <= 0 || terminatesHostCommand(arguments.joinToString(" ") { it.value }, wrappersLeft - 1)

        command in CommandLaunchers ->
            wrappersLeft <= 0 || terminatesHostWrapper(command, arguments, wrappersLeft - 1)

        else -> false
    }
}

/** Skips known wrapper options and their values before examining the executable or shell command payload. */
private fun terminatesHostWrapper(command: String, words: List<HostWord>, wrappersLeft: Int): Boolean {
    val index = wrapperCommandIndex(command, words)
    val option = words.getOrNull(index)?.value?.lowercase() ?: return false
    val hasCommandOption = isShellCommandOption(command, option)
    val hasFileOption = command in PowerShellLaunchers && option in PowerShellFileOptions
    val arguments = words.drop(index + if (hasCommandOption || hasFileOption) 1 else 0)
    return if (hasCommandOption || (command in PowerShellLaunchers && !hasFileOption)) {
        terminatesHostPayload(arguments, wrappersLeft)
    } else {
        terminatesHostWords(arguments, wrappersLeft)
    }
}

private fun wrapperCommandIndex(command: String, words: List<HostWord>): Int {
    var index = 0
    while (index < words.size) {
        val option = words[index].value.lowercase()
        val step = when {
            isShellCommandOption(command, option) ||
                (command in PowerShellLaunchers && option in PowerShellFileOptions) -> 0

            option in wrapperValueOptions(command) -> 2

            option.startsWith('-') || option.matches(CmdSwitch) -> 1

            else -> 0
        }
        if (step == 0) break
        index += step
    }
    return index
}

/** Only a wrapper's quoted command string is parsed again; quoted search patterns never reach this function. */
private fun terminatesHostPayload(words: List<HostWord>, wrappersLeft: Int): Boolean =
    if (words.firstOrNull()?.isQuoted == true) {
        terminatesHostCommand(words.first().value, wrappersLeft)
    } else {
        terminatesHostWords(words, wrappersLeft)
    }

private fun isShellCommandOption(command: String, option: String): Boolean = when (command) {
    "cmd" -> option == "/c" || option == "/k"
    in PowerShellLaunchers -> option in PowerShellCommandOptions
    in PosixShellLaunchers -> option.startsWith('-') && option.drop(1).all { it.isLetter() } && 'c' in option
    else -> false
}

/** Common value-taking switches, rather than treating their value as the executable after a wrapper. */
private fun wrapperValueOptions(command: String): Set<String> = when (command) {
    in PowerShellLaunchers -> PowerShellValueOptions
    in PosixShellLaunchers -> setOf("-o", "--rcfile", "--init-file")
    "sudo", "doas" -> setOf("-u", "--user", "-g", "--group")
    "wsl" -> setOf("-d", "--distribution", "-u", "--user", "--cd")
    else -> emptySet()
}

/** One argument with its outer quotes removed; [isQuoted] identifies a wrapper's quoted command payload. */
private data class HostWord(val value: String, val isQuoted: Boolean)

/**
 * Separates statements and arguments while retaining quoted paths and strings. PowerShell backticks escape the
 * next character outside single quotes. An unbalanced quote consumes the rest of the input; unknown syntax
 * remains subject to turn trust, including full trust.
 */
private class HostCommandScan {
    private val statements = mutableListOf<List<HostWord>>()
    private val words = mutableListOf<HostWord>()
    private val word = StringBuilder()
    private var quote: Char? = null
    private var isEscaped = false
    private var isQuoted = false
    private var isStarted = false

    fun scan(command: String): List<List<HostWord>> {
        command.forEach { take(it) }
        if (isEscaped) word.append('`')
        finishStatement()
        return statements
    }

    private fun take(char: Char) {
        when {
            isEscaped -> {
                append(char)
                isEscaped = false
            }

            char == '`' && quote != '\'' -> {
                isEscaped = true
                isStarted = true
            }

            quote != null -> if (char == quote) quote = null else append(char)

            char in HostQuotes -> {
                if (!isStarted) isQuoted = true
                isStarted = true
                quote = char
            }

            char in HostSeparators -> finishStatement()

            char.isWhitespace() -> finishWord()

            else -> append(char)
        }
    }

    private fun append(char: Char) {
        word.append(char)
        isStarted = true
    }

    private fun finishStatement() {
        finishWord()
        if (words.isNotEmpty()) statements.add(words.toList())
        words.clear()
    }

    private fun finishWord() {
        if (isStarted) words.add(HostWord(word.toString(), isQuoted))
        word.clear()
        isQuoted = false
        isStarted = false
    }
}

/** Bare lower-case name of a command word: no directory and no executable extension. */
private fun String.commandName(): String {
    val file = substringAfterLast('/').substringAfterLast('\\')
    return (if (file.extension().lowercase() in ExecutableExtensions) file.substringBeforeLast('.') else file)
        .lowercase()
}

private fun String.extension(): String = substringAfterLast('.', missingDelimiterValue = "")

/** Flags, `NAME=value` assignments, `cmd` switches and shell call operators preceding an executable. */
private fun String.isIntroducer(): Boolean =
    isEmpty() || startsWith('-') || matches(CmdSwitch) || matches(HostAssignment) || this in HostCallOperators

/** Command separators of `sh`, PowerShell and `cmd`; a redirection such as `2>&1` splits harmlessly. */
private val HostSeparators = setOf(';', '|', '&', '\r', '\n')
private val HostQuotes = setOf('"', '\'')

/** Call operators; `&` also separates statements and therefore does not appear in the scanned words. */
private val HostCallOperators = setOf(".", "&", "&&", "||")

/** A one- or two-letter `cmd` switch; a longer `/`-prefixed word may be an absolute executable path. */
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

/** Wrappers examined for an executable or a shell's command argument, with common value-taking options skipped. */
private val CommandLaunchers = setOf(
    "bash",
    "cmd",
    "doas",
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

private val PowerShellLaunchers = setOf("powershell", "pwsh")
private const val HOST_WRAPPER_LIMIT = 32
private val PosixShellLaunchers = setOf("bash", "sh", "zsh")
private val PowerShellCommandOptions = setOf("-command", "-c")
private val PowerShellFileOptions = setOf("-file", "-f")
private val PowerShellValueOptions = setOf(
    "-encodedcommand",
    "-ec",
    "-executionpolicy",
    "-ep",
    "-inputformat",
    "-outputformat",
    "-version",
    "-windowstyle",
    "-workingdirectory",
)

private val GradleLaunchers = setOf("gradle", "gradlew")
private val GradleStopFlags = setOf("--stop", "-stop")

/** Extensions a host shell runs directly; stripping them also normalises wrapper paths such as `cmd.exe`. */
private val ExecutableExtensions = setOf("bat", "bash", "cmd", "com", "exe", "ps1", "sh", "zsh")
