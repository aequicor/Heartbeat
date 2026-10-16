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
 * Windows PowerShell rebuilds native command lines without escaping embedded double quotes, so they are refused.
 */
internal fun quotedCommand(shell: CommandShell, program: String, arguments: List<String>): String? {
    val words = listOf(program) + arguments
    return when (shell) {
        CommandShell.Posix -> words.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }

        CommandShell.PowerShell -> if (words.any { '"' in it || it.isEmpty() }) {
            null
        } else {
            "& " + words.joinToString(" ") { "'" + it.replace("'", "''") + "'" } + "; exit \$LASTEXITCODE"
        }
    }
}
