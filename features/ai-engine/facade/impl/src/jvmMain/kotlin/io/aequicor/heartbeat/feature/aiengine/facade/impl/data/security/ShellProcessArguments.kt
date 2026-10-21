package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.security

/**
 * Preserves exact native argv through Windows ProcessBuilder's legacy encoder. In that mode embedded quotes
 * require explicit Microsoft CRT escaping; the strict encoder already supplies it. This is not cmd.exe escaping.
 */
internal fun shellProcessArguments(
    command: List<String>,
    isWindows: Boolean = System.getProperty("os.name").startsWith("Windows"),
    isLegacyEncoder: Boolean = hasLegacyWindowsEncoding(),
): List<String> = if (isWindows && isLegacyEncoder) {
    command.mapIndexed { index, argument -> if (index == 0) argument else quoteWindowsArgument(argument) }
} else {
    command
}

private fun hasLegacyWindowsEncoding(): Boolean {
    val defaultValue = if (System.getSecurityManager() == null) "true" else "false"
    return !System.getProperty("jdk.lang.Process.allowAmbiguousCommands", defaultValue)
        .equals("false", ignoreCase = true)
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
