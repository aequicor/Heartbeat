package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import java.io.File

/**
 * Desktop launches do not necessarily inherit a shell's PATH. For the default command, prefer a native CLI on
 * PATH, then check macOS app bundles or the Windows desktop app's materialized CLI cache. Windows cache entries
 * are tried newest first, skipping incomplete entries and caches for other bundled tools. Only native .exe files
 * are used on Windows; npm's .cmd/.bat wrappers cannot be launched directly by ProcessBuilder.
 * Linux also checks the user and system local bin directories. Explicit host configuration and unsupported
 * platforms retain ProcessBuilder's normal command resolution.
 */
internal fun resolveCodexExecutable(
    configured: String,
    osName: String = System.getProperty("os.name"),
    path: String? = System.getenv("PATH"),
    userHome: String = System.getProperty("user.home"),
    localAppData: String? = System.getenv("LOCALAPPDATA"),
    isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
): String {
    if (configured != "codex") return configured
    val isWindows = osName.startsWith("Windows")
    val isMacOs = osName.startsWith("Mac")
    val isLinux = osName.startsWith("Linux")
    if (!isWindows && !isMacOs && !isLinux) return configured
    val pathCandidates = path.orEmpty().split(if (isWindows) ';' else ':').asSequence()
        .map { it.trim().removeSurrounding("\"") }
        .filter(String::isNotBlank)
        .map { File(it, if (isWindows) "codex.exe" else "codex") }
    val installedCandidates = when {
        isWindows -> windowsCodexCandidates(localAppData, userHome)
        isMacOs -> macCodexCandidates(userHome)
        else -> sequenceOf(File(userHome, ".local/bin/codex"), File("/usr/local/bin/codex"))
    }
    return (pathCandidates + installedCandidates).firstOrNull(isExecutable)?.path ?: configured
}

private fun macCodexCandidates(userHome: String): Sequence<File> {
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
    return (appCandidates + cliCandidates).asSequence()
}

private fun windowsCodexCandidates(localAppData: String?, userHome: String): Sequence<File> = sequence {
    val localDirectory = localAppData?.takeIf(String::isNotBlank)?.let(::File) ?: File(userHome, "AppData/Local")
    val cache = File(localDirectory, "OpenAI/Codex/bin")
    val installations = cache.listFiles()?.asSequence().orEmpty().filter(File::isDirectory)
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
    yieldAll(installations.map { File(it, "codex.exe") })
}
