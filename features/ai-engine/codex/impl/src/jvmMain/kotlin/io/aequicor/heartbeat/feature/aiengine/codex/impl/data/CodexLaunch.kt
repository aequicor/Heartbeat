package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigOverride
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import java.io.File

/**
 * How one Codex app-server starts: [executable] and where it came from, the CLI home and the user's `-c`
 * overrides and environment. A non-runnable executable (a Windows npm `.cmd` shim) is reported, never started.
 */
internal data class CodexLaunch(
    val executable: String,
    val source: InstallSource,
    val isRunnable: Boolean,
    val home: String?,
    val overrides: List<ConfigOverride> = emptyList(),
    val environment: List<EnvironmentEntry> = emptyList(),
) {
    override fun toString(): String = "CodexLaunch(source=$source, isRunnable=$isRunnable)"
}

/** Host facts the launch resolution reads; tests replace them. */
internal data class CodexHost(
    val osName: String = System.getProperty("os.name").orEmpty(),
    val osArch: String = System.getProperty("os.arch").orEmpty(),
    val path: String? = System.getenv("PATH"),
    val userHome: String = System.getProperty("user.home").orEmpty(),
    val localAppData: String? = System.getenv("LOCALAPPDATA"),
    val isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
) {
    val isWindows: Boolean get() = osName.startsWith("Windows")
}

/**
 * The launch of [context]: the custom executable of its settings, else Heartbeat's managed copy, else the host
 * configuration's explicit executable, else a system installation (PATH and the standard macOS locations).
 */
internal fun resolveCodexLaunch(
    context: LaunchContext,
    config: CodexLocalConfiguration,
    host: CodexHost = CodexHost(),
): CodexLaunch {
    val settings = context.settings
    val home = settings.homeDirectory?.takeIf { it.isNotBlank() } ?: config.homeDirectory
    val custom = settings.executable?.takeIf { it.isNotBlank() }
    val managed = context.managed?.executable
    val (executable, source, isRunnable) = when {
        custom != null -> Triple(custom, InstallSource.Custom, !custom.isScriptWrapper())

        managed != null -> Triple(managed, InstallSource.Managed, true)

        config.executable != DEFAULT_COMMAND ->
            Triple(config.executable, InstallSource.System, !config.executable.isScriptWrapper())

        else -> systemCodex(host)
    }
    return CodexLaunch(executable, source, isRunnable, home, settings.configOverrides, settings.environment)
}

/** `codex app-server` with the user's overrides first and Heartbeat's isolation flags last, so they win. */
internal fun codexCommand(launch: CodexLaunch, off: CodexNativeOff = CodexNativeOff()): List<String> =
    listOf(launch.executable, "app-server") +
        launch.overrides.flatMap { listOf("-c", "${it.key}=${it.value}") } +
        listOf("-c", "model_provider=\"openai\"") +
        CodexDisabledCapabilities.flatMap { listOf("-c", "features.$it=false") } + off.arguments()

/** The host environment without API keys (the CLI login is used), plus the user's entries and `CODEX_HOME`. */
internal fun applyCodexEnvironment(environment: MutableMap<String, String>, launch: CodexLaunch) {
    listOf("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL").forEach(environment::remove)
    launch.environment.forEach { environment[it.name] = it.value }
    launch.home?.let { environment["CODEX_HOME"] = it }
}

/** File checks of launch settings; they never block saving, the panel shows them. */
internal fun codexLaunchProblems(settings: LaunchSettings, host: CodexHost = CodexHost()): List<LaunchProblem> =
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

/** The package of this host: macOS and Windows on arm64 and x64. */
internal fun codexReleaseTarget(host: CodexHost = CodexHost()): CodexTarget? {
    val arch = when (host.osArch.lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "amd64", "x86_64" -> "x86_64"
        else -> return null
    }
    return when {
        host.osName.startsWith("Mac") -> CodexTarget("$arch-apple-darwin", "bin/codex")
        host.isWindows -> CodexTarget("$arch-pc-windows-msvc", "bin/codex.exe")
        else -> null
    }
}

/** The version `codex --version` prints (`codex-cli 0.159.2`), or null. */
internal fun parseCodexVersion(output: String): String? = VersionPattern.find(output)?.groupValues?.get(1)

private fun systemCodex(host: CodexHost): Triple<String, InstallSource, Boolean> =
    if (host.isWindows) windowsCodex(host) else posixCodex(host)

private fun posixCodex(host: CodexHost): Triple<String, InstallSource, Boolean> {
    val found = host.discover()
    val isFound = found != DEFAULT_COMMAND || pathEntries(host).any { host.isExecutable(File(it, DEFAULT_COMMAND)) }
    return Triple(found, if (isFound) InstallSource.System else InstallSource.Missing, isFound)
}

/**
 * A native `codex.exe` on PATH or in the Codex desktop app's CLI cache. npm installs only a `.cmd` shim: arguments
 * passed through cmd.exe are not safe, so it is shown, not started.
 */
private fun windowsCodex(host: CodexHost): Triple<String, InstallSource, Boolean> {
    val exe = host.discover().takeIf { it != DEFAULT_COMMAND }
    val shim = pathEntries(host).map { File(it, "codex.cmd") }.firstOrNull { it.isFile }
    return when {
        exe != null -> Triple(exe, InstallSource.System, true)
        shim != null -> Triple(shim.path, InstallSource.System, false)
        else -> Triple(DEFAULT_COMMAND, InstallSource.Missing, false)
    }
}

private fun CodexHost.discover(): String = resolveCodexExecutable(
    DEFAULT_COMMAND,
    osName = osName,
    path = path,
    userHome = userHome,
    localAppData = localAppData,
    isExecutable = isExecutable,
)

private fun pathEntries(host: CodexHost): List<String> =
    host.path.orEmpty().split(if (host.isWindows) ';' else ':').filter(String::isNotBlank)

private fun String.isScriptWrapper(): Boolean = endsWith(
    ".cmd",
    ignoreCase = true,
) || endsWith(".bat", ignoreCase = true)

private const val DEFAULT_COMMAND = "codex"
private val VersionPattern = Regex("""(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)""")
