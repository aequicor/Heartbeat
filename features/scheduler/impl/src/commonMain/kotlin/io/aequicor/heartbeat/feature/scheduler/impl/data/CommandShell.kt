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
 * Windows resolves only external applications (including batch files), never cmdlets or PowerShell scripts.
 * Resolution and invocation failures exit nonzero. Typographic single quotes U+2018..U+201B are doubled too.
 * Arguments containing batch separators `, ; =` keep native double quotes, even without whitespace, so batch
 * parameters do not split them. Native `"`, empty words, cmd specials and trailing backslashes in words requiring
 * native quotes are refused. Program wildcards and their escape character are refused before command discovery.
 */
internal fun quotedCommand(shell: CommandShell, program: String, arguments: List<String>): String? {
    val words = listOf(program) + arguments
    return when (shell) {
        CommandShell.Posix -> words.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }

        CommandShell.PowerShell -> if (words.any(::isUnsafeOnWindows) || program.any { it in PROGRAM_WILDCARDS }) {
            null
        } else {
            "try { \$heartbeatCommand = @(Get-Command -Name ${powerShellQuoted(program)} " +
                "-CommandType Application -ErrorAction Stop)[0]; & \$heartbeatCommand.Path " +
                arguments.joinToString(" ", transform = ::powerShellArgument) +
                "; if (\$null -eq \$LASTEXITCODE) { exit 1 }; " +
                "exit \$LASTEXITCODE } catch { Write-Error \$_; exit 1 }"
        }
    }
}

private const val POWERSHELL_QUOTES = "'\u2018\u2019\u201A\u201B"
private const val CMD_SPECIAL = "\"&|<>^%!()\r\n"
private const val CMD_SEPARATORS = ",;="
private const val PROGRAM_WILDCARDS = "*?[]`"

private fun isUnsafeOnWindows(word: String): Boolean = word.isEmpty() || word.any { it in CMD_SPECIAL } ||
    (word.endsWith('\\') && word.any { it.isWhitespace() || it in CMD_SEPARATORS })

private fun powerShellArgument(word: String): String =
    powerShellQuoted(if (word.any { it in CMD_SEPARATORS }) "\"$word\"" else word)

private fun powerShellQuoted(word: String): String = buildString {
    append('\'')
    word.forEach { char -> if (char in POWERSHELL_QUOTES) append(char).append(char) else append(char) }
    append('\'')
}
