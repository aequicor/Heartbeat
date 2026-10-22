package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppState
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostRuntime
import java.io.File

/**
 * Where the Claude desktop app lives on this host and where it keeps its Claude Code runtimes:
 * `claude-code/<version>/<build>/<executable>` under [runtimes]. A build is complete once the app wrote its
 * `.verified` marker next to it. [applications] are the places the app itself may be installed, first match wins.
 * Only paths are known here; nothing of the app's own data is read.
 */
internal data class ClaudeDesktopLayout(
    val applications: List<File>,
    val runtimes: File,
    val executable: String,
    /** macOS: the bundle's `Info.plist`; Windows: the directory of the installer's `app-<version>` folders. */
    val versionSource: File,
)

/**
 * The layout of the Claude desktop app on macOS (`/Applications/Claude.app`, runtimes in the app's data directory)
 * and Windows (the per-user installer in `%LOCALAPPDATA%\AnthropicClaude`, runtimes in `%APPDATA%\Claude`), or null
 * where the app does not exist.
 */
internal fun claudeDesktopLayout(host: ClaudeHost): ClaudeDesktopLayout? = when {
    host.osName.startsWith("Mac") -> {
        val applications = listOf(File("/Applications/Claude.app"), File(host.userHome, "Applications/Claude.app"))
        ClaudeDesktopLayout(
            applications = applications,
            runtimes = File(host.userHome, "Library/Application Support/Claude/claude-code"),
            executable = "claude.app/Contents/MacOS/claude",
            versionSource = File(applications.firstOrNull(host.exists) ?: applications.first(), "Contents/Info.plist"),
        )
    }

    host.isWindows -> {
        val appData = File(host.userHome, "AppData")
        val local = host.environment["LOCALAPPDATA"] ?: File(appData, "Local").path
        val roaming = host.environment["APPDATA"] ?: File(appData, "Roaming").path
        val installer = File(local, "AnthropicClaude")
        ClaudeDesktopLayout(
            applications = listOf(File(installer, "claude.exe")),
            runtimes = File(File(roaming, "Claude"), "claude-code"),
            executable = "claude.exe",
            versionSource = installer,
        )
    }

    else -> null
}

/** Every runtime the app keeps, newest version first and, within a version, the newest build name first. */
internal fun claudeDesktopRuntimes(host: ClaudeHost, layout: ClaudeDesktopLayout): List<HostRuntime> =
    host.entries(layout.runtimes)
        .asSequence()
        .mapNotNull { directory -> directory.name.toVersionParts()?.let { it to directory } }
        .sortedWith(compareByDescending<Pair<List<Int>, File>, List<Int>>(VersionOrder) { it.first })
        .flatMap { (_, version) ->
            host.entries(version).sortedByDescending(File::getName).mapNotNull { build ->
                val executable = File(build, layout.executable)
                if (!host.isExecutable(executable)) return@mapNotNull null
                val isVerified = host.entries(build).any { it.name == DESKTOP_VERIFIED_MARKER }
                HostRuntime(version.name, build.name, isVerified, executable.path)
            }
        }
        .toList()

/**
 * The runtime the app keeps that [version] names (its newest verified build), or the newest verified one without a
 * version. An unverified build is never started: the app may still be writing it.
 */
internal fun claudeDesktopRuntime(host: ClaudeHost, version: String? = null): HostRuntime? {
    val layout = claudeDesktopLayout(host) ?: return null
    return claudeDesktopRuntimes(
        host,
        layout,
    ).firstOrNull { it.isVerified && (version == null || it.version == version) }
}

/**
 * What the file system tells about the Claude desktop app: whether it is installed, its version and the runtimes it
 * keeps. [configDirectory] is the directory the app's runtime shares with every Claude Code CLI by default.
 */
internal fun inspectClaudeDesktop(host: ClaudeHost, configDirectory: String?): HostAppState {
    val layout = claudeDesktopLayout(host) ?: return HostAppState()
    val application = layout.applications.firstOrNull(host.exists)
    return HostAppState(
        isInstalled = application != null,
        version = application?.let { desktopVersion(host, layout) },
        path = application?.path,
        runtimes = claudeDesktopRuntimes(host, layout),
        configDirectory = configDirectory ?: File(host.userHome, ".claude").path,
    )
}

/** The command that opens the installed app, or null when it is not installed. */
internal fun claudeDesktopOpenCommand(host: ClaudeHost): List<String>? {
    val layout = claudeDesktopLayout(host) ?: return null
    val application = layout.applications.firstOrNull(host.exists) ?: return null
    return if (host.isWindows) listOf(application.path) else listOf("/usr/bin/open", "-a", application.path)
}

private fun desktopVersion(host: ClaudeHost, layout: ClaudeDesktopLayout): String? = if (host.isWindows) {
    host.entries(layout.versionSource)
        .mapNotNull { InstallerFolder.matchEntire(it.name)?.groupValues?.get(1) }
        .maxWithOrNull(compareBy(VersionOrder) { it.toVersionParts().orEmpty() })
} else {
    host.readText(layout.versionSource)?.let { BundleVersion.find(it)?.groupValues?.get(1) }
}

private fun String.toVersionParts(): List<Int>? =
    split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.size == VERSION_PARTS }

private val VersionOrder = Comparator<List<Int>> { a, b ->
    a.zip(b).firstNotNullOfOrNull { (x, y) -> x.compareTo(y).takeIf { it != 0 } } ?: a.size.compareTo(b.size)
}

private const val VERSION_PARTS = 3
private const val DESKTOP_VERIFIED_MARKER = ".verified"
private val InstallerFolder = Regex("""app-(\d+\.\d+\.\d+)""")
private val BundleVersion = Regex("""<key>CFBundleShortVersionString</key>\s*<string>([0-9A-Za-z.+-]{1,64})</string>""")
