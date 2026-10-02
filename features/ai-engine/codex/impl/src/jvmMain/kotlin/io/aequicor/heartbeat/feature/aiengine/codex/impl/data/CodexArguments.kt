package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

/**
 * Preserves literal argv with Windows ProcessBuilder's legacy encoder. Its strict encoder already escapes
 * embedded quotes; the legacy one needs fully quoted Microsoft CRT arguments. The executable is left to the JDK.
 */
internal fun codexProcessArguments(
    command: List<String>,
    isWindows: Boolean = System.getProperty("os.name").startsWith("Windows"),
    allowAmbiguousCommands: Boolean = !System.getProperty("jdk.lang.Process.allowAmbiguousCommands")
        .equals("false", ignoreCase = true),
): List<String> = if (isWindows && allowAmbiguousCommands) {
    command.mapIndexed { index, argument -> if (index == 0) argument else quoteWindowsArgument(argument) }
} else {
    command
}

private fun quoteWindowsArgument(argument: String): String = buildString {
    append('"')
    var slashes = 0
    argument.forEach { character ->
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
