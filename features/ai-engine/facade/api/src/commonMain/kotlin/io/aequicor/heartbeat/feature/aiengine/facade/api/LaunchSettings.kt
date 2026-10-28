package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import kotlinx.serialization.Serializable

/** Rollout gate for native MCP access; individual engines additionally require their saved opt-in. */
public val EngineMcpEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "ai.engine_mcp",
    "MCP-серверы, подключённые в ИИ-движках",
    default = false,
)

/**
 * How an engine's processes start in this profile; empty values keep the adapter's defaults. Values may name the
 * user's directories, so they are never logged. Secrets never belong here: they are connections.
 */
@Serializable
public data class LaunchSettings(
    /** Absolute path of the executable to run instead of Heartbeat's choice. */
    val executable: String? = null,
    /** Absolute configuration directory of the CLI (for example `CODEX_HOME`). */
    val homeDirectory: String? = null,
    /** CLI configuration overrides (for example Codex `-c key=value`), applied before Heartbeat's own. */
    val configOverrides: List<ConfigOverride> = emptyList(),
    /** Extra environment variables of the engine's processes. */
    val environment: List<EnvironmentEntry> = emptyList(),
    /** Which installation runs while [executable] is not set; [RuntimePreference.Auto] keeps the adapter's order. */
    val runtime: RuntimePreference = RuntimePreference.Auto,
    /**
     * Version of the host application's runtime to run instead of its newest verified one; only with
     * [RuntimePreference.HostApp].
     */
    val hostRuntimeVersion: String? = null,
    /** Allows MCP servers configured in this engine; off by default and never used by tool-free services. */
    val isMcpEnabled: Boolean = false,
) {
    /** Nothing differs from the adapter's defaults. */
    val isDefault: Boolean
        get() = executable.isNullOrBlank() && homeDirectory.isNullOrBlank() &&
            configOverrides.isEmpty() && environment.isEmpty() && runtime == RuntimePreference.Auto &&
            hostRuntimeVersion.isNullOrBlank() && !isMcpEnabled

    override fun toString(): String = "LaunchSettings(***)"
}

/** One CLI configuration override `key=value`. */
@Serializable
public data class ConfigOverride(val key: String, val value: String) {
    override fun toString(): String = "ConfigOverride(key=$key)"
}

/** One environment variable `name=value`. */
@Serializable
public data class EnvironmentEntry(val name: String, val value: String) {
    override fun toString(): String = "EnvironmentEntry(name=$name)"
}

/**
 * Which installation of an engine runs when no custom executable is set. Only the preferences in
 * [LaunchSpec.runtimes] apply to an engine; a preferred installation that is missing is reported, never replaced by
 * another one.
 */
@Serializable
public enum class RuntimePreference {
    /** The adapter's own order: Heartbeat's copy, a system installation, then the host application's runtime. */
    Auto,

    /** Heartbeat's verified copy. */
    Managed,

    /** The runtime the engine's host application keeps and updates ([ManagementSpec.hostApp]). */
    HostApp,

    /** An installation found on the system (PATH, the native installer, a package manager). */
    System,
}

/** Launch settings an engine accepts. */
public enum class LaunchOption { Executable, HomeDirectory, ConfigOverrides, Environment, Runtime, Mcp }

/**
 * The launch settings an engine honors. [homeVariable] names what [LaunchSettings.homeDirectory] sets. Reserved
 * names and keys are the ones the adapter sets itself to keep sessions isolated; a reserved key `x` also reserves
 * every `x.*` key.
 */
public data class LaunchSpec(
    val options: Set<LaunchOption> = emptySet(),
    val homeVariable: String? = null,
    val reservedEnvironment: Set<String> = emptySet(),
    val reservedEnvironmentPrefixes: Set<String> = emptySet(),
    val reservedConfigKeys: Set<String> = emptySet(),
    /** Runtime preferences besides [RuntimePreference.Auto] the engine honors, with [LaunchOption.Runtime]. */
    val runtimes: Set<RuntimePreference> = emptySet(),
)

/** Launch settings and what the engine's adapter found wrong with them on disk. */
public data class LaunchState(
    val settings: LaunchSettings = LaunchSettings(),
    /** File checks of the saved settings (a missing executable); they never block saving. */
    val warnings: List<LaunchProblem> = emptyList(),
)

/** A problem of one launch setting; [index] points into a list setting. */
public data class LaunchProblem(val option: LaunchOption, val reason: LaunchProblemReason, val index: Int? = null)

/** Why a launch setting is rejected or questionable. */
public enum class LaunchProblemReason {
    /** The engine does not support the setting. */
    Unsupported,

    /** The path is not absolute. */
    NotAbsolute,

    /** On Windows only `.exe` files start directly. */
    NotExe,

    /** A `.cmd` or `.bat` wrapper cannot receive arguments safely. */
    ScriptWrapper,

    /** The variable name is not `[A-Za-z_][A-Za-z0-9_]*`. */
    InvalidName,

    /** The configuration key is not `[A-Za-z0-9_][A-Za-z0-9_.-]*`. */
    InvalidKey,

    /** The adapter sets this name or key itself. */
    Reserved,

    /** The name looks like a credential; secrets belong in connections. */
    Secret,

    /** The name or key appears twice. */
    Duplicate,

    /** The value is too long, or there are too many entries. */
    TooLong,

    /** The value contains a line break or a NUL character. */
    ControlCharacter,

    /** The file or directory does not exist. */
    NotFound,

    /** The file is not an executable. */
    NotExecutable,

    /** The path is not a directory. */
    NotADirectory,

    /** The version is not `[0-9A-Za-z.+-]{1,64}` starting with a letter or digit, or contains `..`. */
    InvalidVersion,
}

/**
 * Checks [settings] against [spec] without touching the disk; any result blocks saving. On
 * [EnginePlatform.DesktopWindows] an executable must be an `.exe`.
 */
public fun validateLaunchSettings(
    settings: LaunchSettings,
    spec: LaunchSpec,
    platform: EnginePlatform?,
): List<LaunchProblem> = buildList {
    settings.executable?.takeIf { it.isNotBlank() }?.let { path ->
        addAll(pathProblems(LaunchOption.Executable, path, spec))
        if (platform == EnginePlatform.DesktopWindows && isAbsolutePath(path)) {
            val name = path.lowercase()
            when {
                name.endsWith(".cmd") || name.endsWith(".bat") ->
                    add(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.ScriptWrapper))

                !name.endsWith(".exe") -> add(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotExe))
            }
        }
    }
    settings.homeDirectory?.takeIf { it.isNotBlank() }?.let { path ->
        addAll(pathProblems(LaunchOption.HomeDirectory, path, spec))
    }
    addAll(overrideProblems(settings.configOverrides, spec))
    addAll(environmentProblems(settings.environment, spec))
    addAll(runtimeProblems(settings, spec))
    if (settings.isMcpEnabled && LaunchOption.Mcp !in spec.options) {
        add(LaunchProblem(LaunchOption.Mcp, LaunchProblemReason.Unsupported))
    }
}

/** Whether [name] looks like a credential variable, which launch settings never carry. */
public fun isSecretVariable(name: String): Boolean {
    val upper = name.uppercase()
    if (upper in SecretNames || SecretPrefixes.any(upper::startsWith)) return true
    if (SecretWords.any(upper::contains)) return true
    return upper.split('_').any { it in SecretSegments }
}

private fun pathProblems(option: LaunchOption, path: String, spec: LaunchSpec): List<LaunchProblem> = when {
    option !in spec.options -> listOf(LaunchProblem(option, LaunchProblemReason.Unsupported))
    path.any { it.isControl() } -> listOf(LaunchProblem(option, LaunchProblemReason.ControlCharacter))
    path.length > MAX_PATH_LENGTH -> listOf(LaunchProblem(option, LaunchProblemReason.TooLong))
    !isAbsolutePath(path) -> listOf(LaunchProblem(option, LaunchProblemReason.NotAbsolute))
    else -> emptyList()
}

private fun overrideProblems(overrides: List<ConfigOverride>, spec: LaunchSpec): List<LaunchProblem> {
    if (overrides.isEmpty()) return emptyList()
    val option = LaunchOption.ConfigOverrides
    if (option !in spec.options) return listOf(LaunchProblem(option, LaunchProblemReason.Unsupported))
    if (overrides.size > MAX_ENTRIES) return listOf(LaunchProblem(option, LaunchProblemReason.TooLong))
    val seen = mutableSetOf<String>()
    return overrides.mapIndexedNotNull { index, entry ->
        val reason = when {
            !ConfigKey.matches(entry.key) -> LaunchProblemReason.InvalidKey

            spec.reservedConfigKeys.any { entry.key == it || entry.key.startsWith("$it.") } ->
                LaunchProblemReason.Reserved

            !seen.add(entry.key) -> LaunchProblemReason.Duplicate

            entry.value.any { it.isControl() } -> LaunchProblemReason.ControlCharacter

            entry.value.length > MAX_VALUE_LENGTH -> LaunchProblemReason.TooLong

            else -> null
        }
        reason?.let { LaunchProblem(option, it, index) }
    }
}

private fun environmentProblems(environment: List<EnvironmentEntry>, spec: LaunchSpec): List<LaunchProblem> {
    if (environment.isEmpty()) return emptyList()
    val option = LaunchOption.Environment
    if (option !in spec.options) return listOf(LaunchProblem(option, LaunchProblemReason.Unsupported))
    if (environment.size > MAX_ENTRIES) return listOf(LaunchProblem(option, LaunchProblemReason.TooLong))
    val reserved = spec.reservedEnvironment.mapTo(mutableSetOf()) { it.uppercase() }
    val prefixes = spec.reservedEnvironmentPrefixes.map { it.uppercase() } + RESERVED_PREFIX
    val seen = mutableSetOf<String>()
    return environment.mapIndexedNotNull { index, entry ->
        // Windows compares variable names case-insensitively; one spelling per name keeps both hosts predictable.
        val name = entry.name.uppercase()
        val reason = when {
            !VariableName.matches(entry.name) -> LaunchProblemReason.InvalidName
            isSecretVariable(entry.name) -> LaunchProblemReason.Secret
            name in reserved || prefixes.any(name::startsWith) -> LaunchProblemReason.Reserved
            !seen.add(name) -> LaunchProblemReason.Duplicate
            entry.value.any { it.isControl() } -> LaunchProblemReason.ControlCharacter
            entry.value.length > MAX_VALUE_LENGTH -> LaunchProblemReason.TooLong
            else -> null
        }
        reason?.let { LaunchProblem(option, it, index) }
    }
}

private fun runtimeProblems(settings: LaunchSettings, spec: LaunchSpec): List<LaunchProblem> {
    val option = LaunchOption.Runtime
    val version = settings.hostRuntimeVersion?.takeIf { it.isNotBlank() }
    if (settings.runtime == RuntimePreference.Auto && version == null) return emptyList()
    val isSupported = option in spec.options &&
        (settings.runtime == RuntimePreference.Auto || settings.runtime in spec.runtimes)
    return when {
        !isSupported -> listOf(LaunchProblem(option, LaunchProblemReason.Unsupported))

        version == null -> emptyList()

        settings.runtime != RuntimePreference.HostApp -> listOf(LaunchProblem(option, LaunchProblemReason.Unsupported))

        !RuntimeVersion.matches(version) || ".." in version ->
            listOf(LaunchProblem(option, LaunchProblemReason.InvalidVersion))

        else -> emptyList()
    }
}

private fun Char.isControl(): Boolean = this == '\u0000' || this == '\n' || this == '\r'

/** `/…` on macOS and Linux, `C:\…`, `C:/…` or `\\server\…` on Windows. */
private fun isAbsolutePath(path: String): Boolean =
    path.startsWith('/') || path.startsWith("\\\\") || WindowsDrive.containsMatchIn(path)

private val WindowsDrive = Regex("""^[A-Za-z]:[\\/]""")
private val VariableName = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val RuntimeVersion = Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}")
private val ConfigKey = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")
private const val RESERVED_PREFIX = "HEARTBEAT_"
private val SecretNames = setOf("CODEX_API_KEY", "CODEX_ACCESS_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN")
private val SecretPrefixes = listOf("ANTHROPIC_", "OPENAI_", "CLAUDE_CODE_USE_")
private val SecretWords = listOf("TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL")
private val SecretSegments = setOf("KEY", "KEYS", "APIKEY")
private const val MAX_ENTRIES = 64
private const val MAX_VALUE_LENGTH = 32_768
private const val MAX_PATH_LENGTH = 4_096
