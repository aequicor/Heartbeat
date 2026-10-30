package io.aequicor.heartbeat.platform.nativelauncher

/** Microsoft C argv quoting: quotes and trailing backslashes survive CreateProcessW without a shell. */
internal fun windowsCommandLine(executable: String, arguments: List<String>): String {
    require(executable.none { it == '\u0000' || it == '"' }) { "Invalid Windows executable path" }
    return (listOf(executable) + arguments).joinToString(" ", transform = ::quoteWindowsArgument)
}

private fun quoteWindowsArgument(argument: String): String = buildString {
    require('\u0000' !in argument) { "NUL in Windows argument" }
    append('"')
    var slashes = 0
    for (character in argument) {
        if (character == '\\') {
            slashes++
        } else {
            repeat(if (character == '"') slashes * 2 + 1 else slashes) { append('\\') }
            append(character)
            slashes = 0
        }
    }
    repeat(slashes * 2) { append('\\') }
    append('"')
}
