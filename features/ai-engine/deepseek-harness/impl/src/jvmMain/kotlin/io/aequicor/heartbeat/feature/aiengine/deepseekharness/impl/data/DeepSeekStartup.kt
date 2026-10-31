package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import java.io.File

/**
 * How the DSH CLI starts: [executable] and where it came from. A null [homeDirectory] keeps DSH's own default
 * (`~/.dsh`); the adapter never creates, downloads or updates the CLI — it discovers the user's installation.
 */
internal data class DeepSeekStartup(
    val executable: String,
    val source: InstallSource,
    val isRunnable: Boolean,
    val homeDirectory: String? = null,
) {
    override fun toString(): String = "DeepSeekStartup(source=$source, isRunnable=$isRunnable)"
}

/** Host facts the startup resolution reads; tests replace them. */
internal data class DeepSeekHost(
    val osName: String = System.getProperty("os.name").orEmpty(),
    val path: String? = System.getenv("PATH"),
    val userHome: String = System.getProperty("user.home").orEmpty(),
    val localAppData: String = System.getenv("LOCALAPPDATA").orEmpty(),
    val isExecutable: (File) -> Boolean = { it.isFile && it.canExecute() },
) {
    val isWindows: Boolean get() = osName.startsWith("Windows")
}

/**
 * Resolves the user-installed `dsh` launcher: the explicit configuration first, then a system installation
 * (PATH, the DeepSeek Harness desktop app's known location). PATH is never trusted to run arbitrary commands.
 */
internal fun resolveDeepSeekStartup(
    configuration: DeepSeekConfiguration,
    host: DeepSeekHost = DeepSeekHost(),
): DeepSeekStartup {
    val configured = configuration.executable.takeIf { it != DEFAULT_COMMAND }
    val found = when {
        configured != null -> FoundDeepSeek(configured, InstallSource.Custom, !configured.isScriptWrapper())

        else -> systemDeepSeek(host)
    }
    return DeepSeekStartup(
        found.executable,
        found.source,
        found.isRunnable,
        configuration.homeDirectory,
    )
}

/**
 * Process environment overrides. The parent environment is inherited (DSH needs its own PATH/runtime), and the
 * adapter pins only the sandbox floor; the permission decision itself belongs to Heartbeat's trust gate.
 */
internal fun dshEnvironment(homeDirectory: String?): Map<String, String> = buildMap {
    put("DSH_PERMISSION_MODE", "workspace-write")
    homeDirectory?.takeIf(String::isNotBlank)?.let { put("DSH_HOME", it) }
}

private fun systemDeepSeek(host: DeepSeekHost): FoundDeepSeek {
    val name = if (host.isWindows) "dsh.cmd" else "dsh"
    val directories = pathEntries(host) + listOfNotNull(
        desktopDeepSeekCli(host),
        "/usr/local/bin".takeIf { !host.isWindows },
    )
    val found = directories.map { File(it, name) }.firstOrNull(host.isExecutable)
    return when {
        found != null -> FoundDeepSeek(found.path, InstallSource.System, true)
        else -> FoundDeepSeek(DEFAULT_COMMAND, InstallSource.Missing, false)
    }
}

/** The CLI shipped inside the DeepSeek Harness desktop app; its updates belong to that app. */
private fun desktopDeepSeekCli(host: DeepSeekHost): String? {
    val app = if (host.isWindows) {
        host.localAppData.takeIf(String::isNotBlank)
            ?.let { File(it, "Programs/DeepSeek Harness/resources/runtime/cli/bin").path }
    } else if (host.osName.startsWith("Mac")) {
        "/Applications/DeepSeek Harness.app/Contents/Resources/resources/runtime/cli/bin"
    } else {
        null
    }
    return app
}

private fun pathEntries(host: DeepSeekHost): List<String> =
    host.path.orEmpty().split(if (host.isWindows) ';' else ':').filter(String::isNotBlank)

private fun String.isScriptWrapper(): Boolean =
    endsWith(".cmd", ignoreCase = true) || endsWith(".bat", ignoreCase = true)

private data class FoundDeepSeek(
    val executable: String,
    val source: InstallSource,
    val isRunnable: Boolean,
)

private const val DEFAULT_COMMAND = "dsh"
