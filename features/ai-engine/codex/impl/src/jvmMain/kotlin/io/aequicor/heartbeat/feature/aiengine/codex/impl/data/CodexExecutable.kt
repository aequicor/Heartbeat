package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import java.io.File

/**
 * Finder launches do not inherit a login shell's PATH. For the default command on macOS, prefer the CLI already
 * on PATH, then check native CLIs in the standard application directories and common CLI installation locations.
 * Explicit host configuration and other platforms retain ProcessBuilder's normal command resolution.
 */
internal fun resolveCodexExecutable(
    configured: String,
    osName: String = System.getProperty("os.name"),
    path: String? = System.getenv("PATH"),
    userHome: String = System.getProperty("user.home"),
    isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
): String {
    if (configured != "codex" || !osName.startsWith("Mac")) return configured
    val pathCandidates = path.orEmpty().split(File.pathSeparatorChar)
        .filter(String::isNotBlank)
        .map { File(it, "codex") }
    val applicationDirectories = listOf(File("/Applications"), File(userHome, "Applications"))
    val appCandidates = applicationDirectories.flatMap { directory ->
        listOf(
            File(directory, "ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex"),
            File(directory, "Codex.app/Contents/Resources/codex"),
        )
    }
    val cliCandidates = listOf(
        File("/opt/homebrew/bin/codex"),
        File("/usr/local/bin/codex"),
        File(userHome, ".local/bin/codex"),
    )
    return (pathCandidates + appCandidates + cliCandidates).firstOrNull(isExecutable)?.path ?: configured
}
