package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
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
 * reported, never started. The runtime of the Claude desktop app ([InstallSource.HostApp]) is updated by that app,
 * so it never updates itself.
 */
internal data class ClaudeStartup(
    val executable: String,
    val source: InstallSource,
    val isRunnable: Boolean,
    val configDirectory: String? = null,
    val environment: List<EnvironmentEntry> = emptyList(),
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
    val environment: Map<String, String> = System.getenv(),
    val exists: (File) -> Boolean = File::exists,
    val readText: (File) -> String? = ::readSmallText,
) {
    val isWindows: Boolean get() = osName.startsWith("Windows")
}

/**
 * The startup of [context]. A custom executable of its settings always wins. Otherwise the runtime preference
 * decides: [RuntimePreference.Managed] runs only Heartbeat's copy, [RuntimePreference.HostApp] only a verified
 * runtime of the Claude desktop app (the pinned version, else the newest) and [RuntimePreference.System] only a
 * system installation; a preferred installation that is missing is reported, never replaced by another one.
 * [RuntimePreference.Auto] tries Heartbeat's copy, the host configuration's explicit executable, a system
 * installation (PATH, the native installer's `~/.local/bin` and Homebrew) and finally the desktop app's runtime.
 * While [isHostAppEnabled] is off the desktop app's runtime is only that last resort. A custom config directory wins
 * over the host configuration's.
 */
internal fun resolveClaudeStartup(
    context: LaunchContext,
    configuration: ClaudeConfiguration,
    host: ClaudeHost = ClaudeHost(),
    isHostAppEnabled: Boolean = true,
): ClaudeStartup {
    val settings = context.settings
    val custom = settings.executable?.takeIf { it.isNotBlank() }
    val managed = context.managed?.executable
    val preference = settings.runtime.takeIf { isHostAppEnabled || it != RuntimePreference.HostApp }
        ?: RuntimePreference.Auto
    val found = if (custom != null) {
        FoundClaude(custom, InstallSource.Custom, !custom.isScriptWrapper())
    } else {
        preferredClaude(preference, settings.hostRuntimeVersion, managed, configuration, host)
    }
    val configDirectory = settings.homeDirectory?.takeIf { it.isNotBlank() } ?: configuration.configDirectory
    return ClaudeStartup(found.executable, found.source, found.isRunnable, configDirectory, settings.environment)
}

/**
 * Host variables passed to the CLI: no API keys or endpoint overrides, then the user's entries. `CLAUDE_CONFIG_DIR`
 * is set only when configured, because an explicit value changes where the CLI looks up its default login.
 * Heartbeat's own copy never updates itself: Heartbeat installs and verifies its updates; the Claude Code desktop
 * app's CLI is updated by that app. Tool search stays off unless the user's entries turn it on: deferred MCP tools
 * hide their full `mcp__<server>__` names, so the model guessed the prefix of Heartbeat's tools and called ones
 * that do not exist.
 */
internal fun claudeEnvironment(host: Map<String, String>, startup: ClaudeStartup): Map<String, String> = buildMap {
    putAll(host.filterKeys { it.uppercase() in HOST_ENVIRONMENT })
    put(TOOL_SEARCH_VARIABLE, "false")
    startup.environment.forEach { put(it.name, it.value) }
    startup.configDirectory?.let { put("CLAUDE_CONFIG_DIR", it) }
    if (startup.source == InstallSource.Managed || startup.source == InstallSource.HostApp) {
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

/**
 * File checks of launch settings; they never block saving, the panel shows them. A preferred desktop app runtime
 * that is not there (no verified build, or not the pinned version) is reported, and so is the preference itself while
 * [isHostAppEnabled] is off, because it then runs as the automatic choice.
 */
internal fun claudeLaunchProblems(
    settings: LaunchSettings,
    host: ClaudeHost = ClaudeHost(),
    isHostAppEnabled: Boolean = true,
): List<LaunchProblem> = buildList {
    settings.executable?.takeIf { it.isNotBlank() }?.let { path ->
        val file = File(path)
        when {
            !file.exists() -> add(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotFound))
            !host.isExecutable(file) -> add(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotExecutable))
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
    val isHostApp = settings.runtime == RuntimePreference.HostApp && settings.executable.isNullOrBlank()
    val version = settings.hostRuntimeVersion?.takeIf { it.isNotBlank() }
    when {
        !isHostApp -> Unit

        // Switched off, the preference runs as the automatic choice; the panel says so instead of hiding it.
        !isHostAppEnabled -> add(LaunchProblem(LaunchOption.Runtime, LaunchProblemReason.Unsupported))

        claudeDesktopRuntime(
            host,
            version,
        ) == null -> add(LaunchProblem(LaunchOption.Runtime, LaunchProblemReason.NotFound))
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

private fun preferredClaude(
    preference: RuntimePreference,
    version: String?,
    managed: String?,
    configuration: ClaudeConfiguration,
    host: ClaudeHost,
): FoundClaude = when (preference) {
    RuntimePreference.Managed -> managed?.let { FoundClaude(it, InstallSource.Managed, true) } ?: MissingClaude

    RuntimePreference.HostApp -> hostAppClaude(host, version?.takeIf { it.isNotBlank() }) ?: MissingClaude

    RuntimePreference.System -> configuredClaude(configuration) ?: systemClaude(host, withHostApp = false)

    RuntimePreference.Auto -> managed?.let { FoundClaude(it, InstallSource.Managed, true) }
        ?: configuredClaude(configuration)
        ?: systemClaude(host, withHostApp = true)
}

private fun configuredClaude(configuration: ClaudeConfiguration): FoundClaude? =
    configuration.executable.takeIf { it != DEFAULT_COMMAND }?.let {
        FoundClaude(it, InstallSource.System, !it.isScriptWrapper())
    }

private fun hostAppClaude(host: ClaudeHost, version: String?): FoundClaude? =
    claudeDesktopRuntime(host, version)?.let { FoundClaude(it.path, InstallSource.HostApp, true) }

private fun systemClaude(host: ClaudeHost, withHostApp: Boolean): FoundClaude {
    val name = if (host.isWindows) "claude.exe" else DEFAULT_COMMAND
    val directories = pathEntries(host) + listOfNotNull(
        File(File(host.userHome, ".local"), "bin").path,
        "/opt/homebrew/bin".takeIf { host.osName.startsWith("Mac") },
        "/usr/local/bin".takeIf { !host.isWindows },
    )
    val found = directories.map { File(it, name) }.firstOrNull(host.isExecutable)
    // npm installs only a `.cmd` shim on Windows: arguments passed through cmd.exe are not safe to start.
    val shim = if (host.isWindows) pathEntries(host).map { File(it, "claude.cmd") }.firstOrNull { it.isFile } else null
    // Only on macOS: the desktop app's runtime on Windows is chosen explicitly until its layout is confirmed there.
    val isFallback = withHostApp && host.osName.startsWith("Mac")
    val desktop = if (found == null && shim == null && isFallback) hostAppClaude(host, null) else null
    return when {
        found != null -> FoundClaude(found.path, InstallSource.System, true)
        shim != null -> FoundClaude(shim.path, InstallSource.System, false)
        desktop != null -> desktop
        else -> MissingClaude
    }
}

private data class FoundClaude(val executable: String, val source: InstallSource, val isRunnable: Boolean)

private val MissingClaude = FoundClaude(DEFAULT_COMMAND, InstallSource.Missing, false)

/** A text file of at most [MAX_TEXT_BYTES], or null when it is absent, larger or unreadable. */
private fun readSmallText(file: File): String? = try {
    file.takeIf { it.isFile && it.length() <= MAX_TEXT_BYTES }?.readText()
} catch (e: IOException) {
    // The message names the file's path, which never reaches the logs.
    Log.tag("ClaudeStartup").w(e.redacted()) { "A Claude desktop file could not be read" }
    null
}

private fun pathEntries(host: ClaudeHost): List<String> =
    host.path.orEmpty().split(if (host.isWindows) ';' else ':').filter(String::isNotBlank)

private fun String.isScriptWrapper(): Boolean = endsWith(
    ".cmd",
    ignoreCase = true,
) || endsWith(".bat", ignoreCase = true)

private const val DEFAULT_COMMAND = "claude"
private const val TOOL_SEARCH_VARIABLE = "ENABLE_TOOL_SEARCH"
private const val MAX_TEXT_BYTES = 1_048_576L
private val VersionPattern = Regex("""(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)""")
private val HOST_ENVIRONMENT = setOf(
    "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
    "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "APPDATA", "LOCALAPPDATA", "PROGRAMFILES", "PROGRAMFILES(X86)",
    "USER", "LOGNAME", "USERNAME", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "NODE_EXTRA_CA_CERTS", "SSL_CERT_FILE",
)
