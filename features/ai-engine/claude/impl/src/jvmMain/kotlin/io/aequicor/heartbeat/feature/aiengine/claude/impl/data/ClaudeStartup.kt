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
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * How the CLI starts: [executable] and where it came from, the config directory (`CLAUDE_CONFIG_DIR`, null keeps
 * the CLI's default) and the user's environment entries. A non-runnable executable (a Windows `.cmd` shim) is
 * reported, never started.
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
    val isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
) {
    val isWindows: Boolean get() = osName.startsWith("Windows")
}

/**
 * The startup of [context]: the custom executable of its settings, else Heartbeat's managed copy, else the host
 * configuration's explicit executable, else a system installation (PATH, the native installer's `~/.local/bin`
 * and Homebrew). A custom config directory wins over the host configuration's.
 */
internal fun resolveClaudeStartup(
    context: LaunchContext,
    configuration: ClaudeConfiguration,
    host: ClaudeHost = ClaudeHost(),
): ClaudeStartup {
    val settings = context.settings
    val custom = settings.executable?.takeIf { it.isNotBlank() }
    val managed = context.managed?.executable
    val (executable, source, isRunnable) = when {
        custom != null -> Triple(custom, InstallSource.Custom, !custom.isScriptWrapper())

        managed != null -> Triple(managed, InstallSource.Managed, true)

        configuration.executable != DEFAULT_COMMAND -> Triple(
            configuration.executable,
            InstallSource.System,
            !configuration.executable.isScriptWrapper(),
        )

        else -> systemClaude(host)
    }
    val configDirectory = settings.homeDirectory?.takeIf { it.isNotBlank() } ?: configuration.configDirectory
    return ClaudeStartup(executable, source, isRunnable, configDirectory, settings.environment)
}

/**
 * Host variables passed to the CLI: no API keys or endpoint overrides, then the user's entries. `CLAUDE_CONFIG_DIR`
 * is set only when configured, because an explicit value changes where the CLI looks up its default login.
 * Heartbeat's own copy never updates itself: Heartbeat installs and verifies its updates.
 */
internal fun claudeEnvironment(host: Map<String, String>, startup: ClaudeStartup): Map<String, String> = buildMap {
    putAll(host.filterKeys { it.uppercase() in HOST_ENVIRONMENT })
    startup.environment.forEach { put(it.name, it.value) }
    startup.configDirectory?.let { put("CLAUDE_CONFIG_DIR", it) }
    if (startup.source == InstallSource.Managed) {
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

/** The release platform of this host (`darwin-arm64`, `win32-x64`…), or null where Claude Code publishes none. */
internal fun claudeReleasePlatform(host: ClaudeHost = ClaudeHost()): String? {
    val arch = when (host.osArch.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> return null
    }
    return when {
        host.osName.startsWith("Mac") -> "darwin-$arch"
        host.isWindows -> "win32-$arch"
        else -> null
    }
}

/** The version `claude --version` prints (`2.1.3 (Claude Code)`), or null. */
internal fun parseClaudeVersion(output: String): String? = VersionPattern.find(output)?.groupValues?.get(1)

private fun systemClaude(host: ClaudeHost): Triple<String, InstallSource, Boolean> {
    val name = if (host.isWindows) "claude.exe" else DEFAULT_COMMAND
    val directories = pathEntries(host) + listOfNotNull(
        File(File(host.userHome, ".local"), "bin").path,
        "/opt/homebrew/bin".takeIf { !host.isWindows },
        "/usr/local/bin".takeIf { !host.isWindows },
    )
    val found = directories.map { File(it, name) }.firstOrNull(host.isExecutable)
    // npm installs only a `.cmd` shim on Windows: arguments passed through cmd.exe are not safe to start.
    val shim = if (host.isWindows) pathEntries(host).map { File(it, "claude.cmd") }.firstOrNull { it.isFile } else null
    return when {
        found != null -> Triple(found.path, InstallSource.System, true)
        shim != null -> Triple(shim.path, InstallSource.System, false)
        else -> Triple(DEFAULT_COMMAND, InstallSource.Missing, false)
    }
}

private fun pathEntries(host: ClaudeHost): List<String> =
    host.path.orEmpty().split(if (host.isWindows) ';' else ':').filter(String::isNotBlank)

private fun String.isScriptWrapper(): Boolean = endsWith(
    ".cmd",
    ignoreCase = true,
) || endsWith(".bat", ignoreCase = true)

private const val DEFAULT_COMMAND = "claude"
private val VersionPattern = Regex("""(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)""")
private val HOST_ENVIRONMENT = setOf(
    "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
    "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "APPDATA", "LOCALAPPDATA", "PROGRAMFILES", "PROGRAMFILES(X86)",
    "USER", "LOGNAME", "USERNAME", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "NODE_EXTRA_CA_CERTS", "SSL_CERT_FILE",
)
