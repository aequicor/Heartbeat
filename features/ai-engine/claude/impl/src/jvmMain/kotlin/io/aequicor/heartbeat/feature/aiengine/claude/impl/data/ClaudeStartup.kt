package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/**
 * How the CLI starts: [executable] and where it came from, the config directory (`CLAUDE_CONFIG_DIR`, null keeps
 * the CLI's default) and the user's environment entries. A non-runnable executable (a Windows `.cmd` shim) is
 * reported, never started. [isDesktopBundle] marks the CLI that the Claude Code desktop app ships: its updates
 * belong to that app, so the CLI never updates itself.
 */
internal data class ClaudeStartup(
    val executable: String,
    val source: InstallSource,
    val isRunnable: Boolean,
    val configDirectory: String? = null,
    val environment: List<EnvironmentEntry> = emptyList(),
    val isDesktopBundle: Boolean = false,
) {
    override fun toString(): String = "ClaudeStartup(source=$source, isRunnable=$isRunnable)"
}

/** Host facts the startup resolution reads; tests replace them. */
internal data class ClaudeHost(
    val osName: String = System.getProperty("os.name").orEmpty(),
    val osArch: String = System.getProperty("os.arch").orEmpty(),
    val path: String? = System.getenv("PATH"),
    val userHome: String = System.getProperty("user.home").orEmpty(),
    val entries: (File) -> List<File> = { it.listFiles()?.toList().orEmpty() },
    val isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
) {
    val isWindows: Boolean get() = osName.startsWith("Windows")
}

/**
 * The startup of [context]: the custom executable of its settings, else Heartbeat's managed copy, else the host
 * configuration's explicit executable, else a system installation (PATH, the native installer's `~/.local/bin`
 * and Homebrew), else, on macOS only, the CLI of the Claude Code desktop app, which shares that app's login.
 * A custom config directory wins over the host configuration's.
 */
internal fun resolveClaudeStartup(
    context: LaunchContext,
    configuration: ClaudeConfiguration,
    host: ClaudeHost = ClaudeHost(),
): ClaudeStartup {
    val settings = context.settings
    val custom = settings.executable?.takeIf { it.isNotBlank() }
    val managed = context.managed?.executable
    val found = when {
        custom != null -> FoundClaude(custom, InstallSource.Custom, !custom.isScriptWrapper())

        managed != null -> FoundClaude(managed, InstallSource.Managed, true)

        configuration.executable != DEFAULT_COMMAND -> FoundClaude(
            configuration.executable,
            InstallSource.System,
            !configuration.executable.isScriptWrapper(),
        )

        else -> systemClaude(host)
    }
    val configDirectory = settings.homeDirectory?.takeIf { it.isNotBlank() } ?: configuration.configDirectory
    return ClaudeStartup(
        found.executable,
        found.source,
        found.isRunnable,
        configDirectory,
        settings.environment,
        found.isDesktopBundle,
    )
}

/**
 * Host variables passed to the CLI: no API keys or endpoint overrides, then the user's entries. `CLAUDE_CONFIG_DIR`
 * is set only when configured, because an explicit value changes where the CLI looks up its default login.
 * Heartbeat's own copy never updates itself: Heartbeat installs and verifies its updates; the Claude Code desktop
 * app's CLI is updated by that app.
 */
internal fun claudeEnvironment(host: Map<String, String>, startup: ClaudeStartup): Map<String, String> = buildMap {
    putAll(host.filterKeys { it.uppercase() in HOST_ENVIRONMENT })
    startup.environment.forEach { put(it.name, it.value) }
    startup.configDirectory?.let { put("CLAUDE_CONFIG_DIR", it) }
    if (startup.source == InstallSource.Managed || startup.isDesktopBundle) {
        put("DISABLE_AUTOUPDATER", "1")
        put("DISABLE_UPDATES", "1")
    }
}

/** Opaque fingerprint of the native history directory of [configDirectory]; a changed store cannot resume. */
internal fun claudeNativeStore(configDirectory: String?, userHome: String = System.getProperty("user.home")): String {
    val root = configDirectory ?: Path.of(userHome, ".claude").toString()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(Path.of(root).toAbsolutePath().normalize().toString().toByteArray())
    return HexFormat.of().formatHex(digest)
}

/**
 * Whether the native history under [configDirectory] (the CLI's default when null) holds the conversation [id]:
 * a `<id>.jsonl` file in any project directory. A history that does not exist holds none. Throws [IOException] when
 * it exists but cannot be read.
 */
internal fun claudeTranscriptExists(
    configDirectory: String?,
    id: String,
    userHome: String = System.getProperty("user.home"),
): Boolean {
    val projects = Path.of(configDirectory ?: Path.of(userHome, ".claude").toString(), "projects")
    if (Files.notExists(projects)) return false
    return try {
        Files.newDirectoryStream(projects).use { directories ->
            // A stray file next to the project directories (`.DS_Store`) holds no conversation; stat-ing below it
            // fails with "Not a directory" on POSIX hosts, so only directories are searched.
            directories.any { isDirectory(it) && isTranscript(it.resolve("$id.jsonl")) }
        }
    } catch (e: DirectoryIteratorException) {
        // The stream reports a failed read of its entries unchecked; callers handle only IOException.
        throw e.cause ?: IOException("The history directory could not be listed", e)
    }
}

/** Like [isTranscript]: an entry that cannot be told from absent (no permission to stat it) throws. */
private fun isDirectory(path: Path): Boolean =
    !Files.notExists(path) && Files.readAttributes(path, BasicFileAttributes::class.java).isDirectory

/** A path that cannot be told from absent (no permission to stat it) throws instead of reading as no history. */
private fun isTranscript(path: Path): Boolean =
    !Files.notExists(path) && Files.readAttributes(path, BasicFileAttributes::class.java).isRegularFile

/** File checks of launch settings; they never block saving, the panel shows them. */
internal fun claudeLaunchProblems(settings: LaunchSettings, host: ClaudeHost = ClaudeHost()): List<LaunchProblem> =
    buildList {
        settings.executable?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            when {
                !file.exists() -> add(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotFound))

                !host.isExecutable(
                    file,
                ) -> add(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotExecutable))
            }
        }
        settings.homeDirectory?.takeIf { it.isNotBlank() }?.let { path ->
            val directory = File(path)
            when {
                !directory.exists() -> add(LaunchProblem(LaunchOption.HomeDirectory, LaunchProblemReason.NotFound))

                !directory.isDirectory -> add(
                    LaunchProblem(LaunchOption.HomeDirectory, LaunchProblemReason.NotADirectory),
                )
            }
        }
    }

/** The release platform on macOS, Windows or glibc Linux, or null where Claude Code publishes none. */
internal fun claudeReleasePlatform(host: ClaudeHost = ClaudeHost()): String? {
    val arch = when (host.osArch.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> return null
    }
    return when {
        host.osName.startsWith("Mac") -> "darwin-$arch"
        host.isWindows -> "win32-$arch"
        host.osName.startsWith("Linux") -> "linux-$arch"
        else -> null
    }
}

/** The version `claude --version` prints (`2.1.3 (Claude Code)`), or null. */
internal fun parseClaudeVersion(output: String): String? = VersionPattern.find(output)?.groupValues?.get(1)

private fun systemClaude(host: ClaudeHost): FoundClaude {
    val name = if (host.isWindows) "claude.exe" else DEFAULT_COMMAND
    val directories = pathEntries(host) + listOfNotNull(
        File(File(host.userHome, ".local"), "bin").path,
        "/opt/homebrew/bin".takeIf { host.osName.startsWith("Mac") },
        "/usr/local/bin".takeIf { !host.isWindows },
    )
    val found = directories.map { File(it, name) }.firstOrNull(host.isExecutable)
    // npm installs only a `.cmd` shim on Windows: arguments passed through cmd.exe are not safe to start.
    val shim = if (host.isWindows) pathEntries(host).map { File(it, "claude.cmd") }.firstOrNull { it.isFile } else null
    val desktop = if (found == null && shim == null) desktopClaude(host) else null
    return when {
        found != null -> FoundClaude(found.path, InstallSource.System, true)
        shim != null -> FoundClaude(shim.path, InstallSource.System, false)
        desktop != null -> FoundClaude(desktop.path, InstallSource.System, true, isDesktopBundle = true)
        else -> FoundClaude(DEFAULT_COMMAND, InstallSource.Missing, false)
    }
}

/**
 * The CLI the Claude Code desktop app keeps on macOS, `claude-code/<version>/<build>/claude.app/Contents/MacOS/claude`
 * in the app's data directory: the newest version whose build carries the desktop's `.verified` marker (only its
 * presence is checked; the binary is trusted like any CLI the user installed). It reads the same login as every
 * Claude Code CLI, so a desktop user needs no second installation or sign-in.
 */
private fun desktopClaude(host: ClaudeHost): File? {
    if (!host.osName.startsWith("Mac")) return null
    val root = File(host.userHome, "Library/Application Support/Claude/claude-code")
    return host.entries(root)
        .mapNotNull { directory -> directory.name.toVersionParts()?.let { it to directory } }
        .sortedWith(compareByDescending<Pair<List<Int>, File>, List<Int>>(VersionOrder) { it.first })
        .asSequence()
        // Lazy: the builds of an older version are listed only when no newer one qualified.
        .flatMap { (_, version) -> host.entries(version).sortedByDescending(File::getName) }
        .firstOrNull { build ->
            host.entries(build).any { it.name == DESKTOP_VERIFIED_MARKER } &&
                host.isExecutable(File(build, DESKTOP_EXECUTABLE))
        }
        ?.let { File(it, DESKTOP_EXECUTABLE) }
}

private fun String.toVersionParts(): List<Int>? =
    split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.size == VERSION_PARTS }

private val VersionOrder = Comparator<List<Int>> { a, b ->
    a.zip(b).firstNotNullOfOrNull { (x, y) -> x.compareTo(y).takeIf { it != 0 } } ?: a.size.compareTo(b.size)
}

private data class FoundClaude(
    val executable: String,
    val source: InstallSource,
    val isRunnable: Boolean,
    val isDesktopBundle: Boolean = false,
)

private fun pathEntries(host: ClaudeHost): List<String> =
    host.path.orEmpty().split(if (host.isWindows) ';' else ':').filter(String::isNotBlank)

private fun String.isScriptWrapper(): Boolean = endsWith(
    ".cmd",
    ignoreCase = true,
) || endsWith(".bat", ignoreCase = true)

private const val DEFAULT_COMMAND = "claude"
private const val VERSION_PARTS = 3
private const val DESKTOP_VERIFIED_MARKER = ".verified"
private const val DESKTOP_EXECUTABLE = "claude.app/Contents/MacOS/claude"
private val VersionPattern = Regex("""(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)""")
private val HOST_ENVIRONMENT = setOf(
    "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
    "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "APPDATA", "LOCALAPPDATA", "PROGRAMFILES", "PROGRAMFILES(X86)",
    "USER", "LOGNAME", "USERNAME", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "NODE_EXTRA_CA_CERTS", "SSL_CERT_FILE",
)
