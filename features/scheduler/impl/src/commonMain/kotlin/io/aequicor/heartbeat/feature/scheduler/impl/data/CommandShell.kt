package io.aequicor.heartbeat.feature.scheduler.impl.data

/** The shell a [CommandRunner] passes its command text to; argument lists are quoted for it. */
internal enum class CommandShell {
    /** `/bin/sh -c`. */
    Posix,

    /** Windows PowerShell `-EncodedCommand`. */
    PowerShell,
}

/**
 * Command text that makes [shell] run [program] with exactly [arguments], or null when the shell cannot pass them
 * unchanged. Every word is quoted literally: no expansion, globbing, redirection or command separators apply.
 *
 * Windows PowerShell needs more: it treats the typographic quotes U+2018..U+201B as single quotes too (each is
 * doubled), rebuilds native command lines without escaping `"` or a trailing backslash before its closing quote,
 * and batch files (`.bat`, `.cmd`, which `gradlew.bat` is) are parsed again by `cmd.exe`, where `& | < > ^ % !` and
 * parentheses run commands or expand variables even inside quotes. Words with any of those, empty words and line
 * breaks are refused.
 */
internal fun quotedCommand(shell: CommandShell, program: String, arguments: List<String>): String? {
    val words = listOf(program) + arguments
    return when (shell) {
        CommandShell.Posix -> words.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }

        CommandShell.PowerShell -> if (words.any(::isUnsafeOnWindows)) {
            null
        } else {
            "& " + words.joinToString(" ", transform = ::powerShellQuoted) + "; exit \$LASTEXITCODE"
        }
    }
}

private const val POWERSHELL_QUOTES = "'\u2018\u2019\u201A\u201B"
private const val CMD_SPECIAL = "\"&|<>^%!()\r\n"

private fun isUnsafeOnWindows(word: String): Boolean = word.isEmpty() || word.any { it in CMD_SPECIAL } ||
    (word.endsWith('\\') && word.any { it.isWhitespace() })

private fun powerShellQuoted(word: String): String = buildString {
    append('\'')
    word.forEach { char -> if (char in POWERSHELL_QUOTES) append(char).append(char) else append(char) }
    append('\'')
}
