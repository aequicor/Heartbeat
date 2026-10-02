package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

/**
 * Engine management in the engine settings: every registered engine with its switch, installation, CLI login,
 * launch settings and runtimes. Off by default; while off, profile switches and launch settings are ignored and
 * engines run exactly as their developer flags and defaults say.
 */
public val EngineManagementEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "ai.engine_management",
    "Управление ИИ-движками: установка, вход, параметры запуска",
    default = false,
)

/**
 * Profile control surface over every registered engine, including disabled and unsupported ones. Reading [state]
 * never probes, installs or signs in. Long operations (downloads, browser sign-in) are jobs owned by the profile:
 * they outlive the screen that started them and report their progress in [ManagedEngine.job]. One job runs per
 * engine at a time. While [EngineManagementEnabled] is off, [state] is [EngineManagementState.Off].
 */
public interface EngineManagement {
    /** Current management state and its changes. */
    public val state: StateFlow<EngineManagementState>

    /**
     * Applies [command] to [engine]. A [EngineCommand.Start] returns once its job started; its outcome arrives in
     * [state]. Throws [EngineException]: Busy while another job of the engine runs or a runtime is executing a turn,
     * Request.Invalid for launch settings that fail [validateLaunchSettings], Access.OperationNotAllowed when the
     * command does not apply to the engine (see [ManagedEngine.actions]) or management is off.
     */
    public suspend fun execute(engine: EngineId, command: EngineCommand)
}

/** Management of a host without the facade implementation; always [EngineManagementState.Off]. */
public class UnavailableEngineManagement : EngineManagement {
    private val off = MutableStateFlow(EngineManagementState.Off)

    override val state: StateFlow<EngineManagementState> = off.asStateFlow()

    override suspend fun execute(engine: EngineId, command: EngineCommand): Nothing =
        throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
}

/** Every registered engine in catalog order; [platform] is the host, null where no engine can run. */
public data class EngineManagementState(
    val isEnabled: Boolean = false,
    val platform: EnginePlatform? = null,
    val engines: List<ManagedEngine> = emptyList(),
) {
    /** Constants of the state. */
    public companion object {
        /** Management switched off or unavailable. */
        public val Off: EngineManagementState = EngineManagementState()
    }
}

/** One registered engine as the management panel sees it. */
public data class ManagedEngine(
    val descriptor: EngineDescriptor,
    val spec: ManagementSpec,
    val enablement: EngineEnablement,
    val availability: EngineAvailability,
    /** Saved connections (bindings) of the engine. */
    val connections: Int = 0,
    val installation: InstallationState = InstallationState(),
    val login: LoginState = LoginState.NotApplicable,
    val launch: LaunchState = LaunchState(),
    val runtime: RuntimeSummary = RuntimeSummary(),
    /** Running job, or the outcome of the last one until it is dismissed. */
    val job: EngineJob? = null,
)

/**
 * Whether an engine may run and why not. [isUserEnabled] is the profile switch; developer flags, the catalog flag
 * and the host platform can each switch the engine off independently of it.
 */
public data class EngineEnablement(val isUserEnabled: Boolean = true, val reasons: Set<DisabledReason> = emptySet()) {
    /** The engine is listed, executed and connectable. */
    val isEnabled: Boolean get() = reasons.isEmpty()

    /** Only the profile switch keeps the engine off; switching it on enables the engine. */
    val isOnlyUserDisabled: Boolean get() = reasons == setOf(DisabledReason.DisabledByUser)
}

/** Why an engine is off. */
public enum class DisabledReason {
    /** The catalog flag [AiEngines] is off. */
    CatalogFlagOff,

    /** The engine's own developer flag ([EngineDescriptor.toggle]) is off. */
    EngineFlagOff,

    /** Switched off in this profile. */
    DisabledByUser,

    /** The engine does not run on this platform. */
    UnsupportedPlatform,
}

/** What the panel may manage for an engine; static, declared by its registration. */
public data class ManagementSpec(
    val install: InstallSupport = InstallSupport.BuiltIn,
    val login: LoginSupport = LoginSupport.None,
    val launch: LaunchSpec = LaunchSpec(),
)

/** How an engine reaches the device. */
public enum class InstallSupport {
    /** Part of Heartbeat itself (an HTTP client): nothing to install, update or remove. */
    BuiltIn,

    /** An external CLI: Heartbeat installs its own verified copy next to any system installation. */
    Managed,

    /** Ships with Heartbeat; a newer verified copy may replace the bundled one and be removed again. */
    Bundled,
}

/** How an engine signs in. */
public enum class LoginSupport {
    /** No authorization. */
    None,

    /** API keys and accounts are added as connections (the connection wizard). */
    Connections,

    /** The engine's CLI signs in through a browser. */
    Cli,

    /** The engine's CLI signs in through a browser or with a device code. */
    CliWithDeviceCode,
}

/** Where the executable an engine runs comes from. */
public enum class InstallSource {
    /** Part of Heartbeat; there is no executable. */
    BuiltIn,

    /** The copy shipped inside Heartbeat. */
    Bundled,

    /** A copy Heartbeat installed and verified. */
    Managed,

    /** Found on the system: PATH, a package manager or another application. */
    System,

    /** The executable set in the launch settings. */
    Custom,

    /** Nothing runnable was found. */
    Missing,
}

/** The executable an engine would run now. [path] is for display only and is never logged. */
public data class Installation(
    val source: InstallSource,
    val version: String? = null,
    val path: String? = null,
    /** False when the found executable cannot be started safely (e.g. a Windows `.cmd` shim). */
    val isRunnable: Boolean = true,
) {
    override fun toString(): String = "Installation(source=$source, version=$version, isRunnable=$isRunnable)"
}

/** A copy Heartbeat installed; [executable] is absolute and never logged. */
public data class ManagedInstall(val version: String, val executable: String, val installedAt: Instant) {
    override fun toString(): String = "ManagedInstall(version=$version, installedAt=$installedAt)"
}

/** Whether a version was verified with this Heartbeat build. */
public enum class Compatibility {
    /** The version Heartbeat ships or was tested with. */
    Verified,

    /** A newer release Heartbeat was not tested with; it may be rolled back. */
    Unverified,

    /** Nothing is known. */
    Unknown,
}

/** Installation facts of an engine; [current] stays null until an explicit inspection. */
public data class InstallationState(
    val current: Installation? = null,
    val managed: ManagedInstall? = null,
    /** Version shipped inside Heartbeat, for [InstallSupport.Bundled]. */
    val bundledVersion: String? = null,
    /** Newest release known from the last update check. */
    val latest: String? = null,
    val compatibility: Compatibility = Compatibility.Unknown,
    val checkedAt: Instant? = null,
    /** Why the last inspection or update check failed. */
    val failure: EngineFailure? = null,
) {
    /** A newer release than the copy Heartbeat would replace (managed, else bundled, else current) is known. */
    val isUpdateAvailable: Boolean
        get() {
            val installed = managed?.version ?: bundledVersion ?: current?.version
            return latest != null && (installed == null || isNewerVersion(latest, installed))
        }
}

/** CLI sign-in state; account details are never logged. */
public sealed interface LoginState {
    /** The engine signs in through connections or not at all. */
    public data object NotApplicable : LoginState

    /** Not checked yet, or the check failed. */
    public data object Unknown : LoginState

    /** The CLI is not signed in. */
    public data object SignedOut : LoginState

    /** The CLI is signed in, as [account] when the CLI reports it. */
    public data class SignedIn(val account: String? = null) : LoginState {
        override fun toString(): String = "SignedIn(account=${if (account == null) "unknown" else "***"})"
    }
}

/** Runtimes of an engine in this profile. */
public data class RuntimeSummary(
    val runtimes: Int = 0,
    val openSessions: Int = 0,
    val activeTurns: Int = 0,
    /** A running runtime started before the engine's launch settings or installation changed. */
    val isStale: Boolean = false,
    /** A runtime shut itself down (crash, account change) and is replaced on next use. */
    val hasExited: Boolean = false,
    val startedAt: Instant? = null,
) {
    /** Restarting stops idle runtimes; a turn in flight is never interrupted. */
    val canRestart: Boolean get() = runtimes > 0 && activeTurns == 0
}

/** A management request for one engine. */
public sealed interface EngineCommand {
    /** Switches the engine on or off in this profile; switching off stops its idle runtimes. */
    public data class SetEnabled(val isEnabled: Boolean) : EngineCommand

    /** Inspects the installation (version, path, source) and the CLI login; never installs or signs in. */
    public data object Inspect : EngineCommand

    /** Looks up the newest release. */
    public data object CheckForUpdates : EngineCommand

    /** Saves launch settings and restarts idle runtimes so new sessions use them. */
    public data class Configure(val settings: LaunchSettings) : EngineCommand

    /** Stops idle runtimes; the next session starts them again. */
    public data object Restart : EngineCommand

    /** Starts a job. */
    public data class Start(val action: EngineAction) : EngineCommand

    /** Cancels the running job; partial downloads are removed. */
    public data object Cancel : EngineCommand

    /** Clears the outcome of the last finished job. */
    public data object Dismiss : EngineCommand

    /** Passes the code a sign-in page shows to the CLI that asked for it. */
    public data class AnswerLogin(val code: LoginCode) : EngineCommand
}

/** A long operation of an engine. */
public sealed interface EngineAction {
    /** Installs Heartbeat's copy of the newest release. */
    public data object Install : EngineAction

    /** Replaces Heartbeat's (or the bundled) copy with the newest release. */
    public data object Update : EngineAction

    /** Removes Heartbeat's copy; a bundled or system installation stays. */
    public data object Uninstall : EngineAction

    /** Signs the CLI in. */
    public data class Login(val method: LoginMethod = LoginMethod.Browser) : EngineAction

    /** Signs the CLI out. */
    public data object Logout : EngineAction
}

/** How a CLI signs in. */
public enum class LoginMethod {
    /** A sign-in page in the browser. */
    Browser,

    /** A code entered on the provider's device page. */
    DeviceCode,
}

/** A one-time code from a sign-in page; never logged. */
public class LoginCode(public val value: String) {
    override fun equals(other: Any?): Boolean = other is LoginCode && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "LoginCode(***)"
}

/** Kinds of management actions, for the panel's buttons. */
public enum class EngineActionKind {
    Inspect,
    CheckForUpdates,
    Install,
    Update,
    Uninstall,
    Login,
    Logout,
    Restart,
    Configure,
}

/** A running or finished job. */
public data class EngineJob(val action: EngineAction, val phase: JobPhase, val startedAt: Instant)

/** Progress of a job. */
public sealed interface JobPhase {
    /** No further progress follows. */
    public val isFinished: Boolean get() = false

    /** Resolving the release or preparing the CLI. */
    public data object Preparing : JobPhase

    /** Downloading [bytes] of [total] (null when the size is unknown). */
    public data class Downloading(val bytes: Long, val total: Long? = null) : JobPhase

    /** Checking the download's checksum. */
    public data object Verifying : JobPhase

    /** Unpacking the archive. */
    public data object Unpacking : JobPhase

    /** Checking that the new copy starts and reports the expected version. */
    public data object Checking : JobPhase

    /** Switching to the new copy. */
    public data object Activating : JobPhase

    /** Removing Heartbeat's copy. */
    public data object Removing : JobPhase

    /** The user signs in at [url]; with a device code, [userCode] is entered there. */
    public data class AwaitingBrowser(val url: String, val userCode: String? = null) : JobPhase {
        override fun toString(): String = "AwaitingBrowser(***)"
    }

    /** The CLI waits for the code the sign-in page at [url] shows. */
    public data class AwaitingCode(val url: String? = null) : JobPhase {
        override fun toString(): String = "AwaitingCode(***)"
    }

    /** The job completed. */
    public data object Succeeded : JobPhase {
        override val isFinished: Boolean get() = true
    }

    /** The job failed with [failure]; nothing half-installed stays active. */
    public data class Failed(val failure: ManagementFailure) : JobPhase {
        override val isFinished: Boolean get() = true
    }

    /** The job was cancelled. */
    public data object Cancelled : JobPhase {
        override val isFinished: Boolean get() = true
    }
}

/** Why a job failed. */
public sealed interface ManagementFailure {
    /** A failure of the engine or its environment. */
    public data class Engine(val failure: EngineFailure) : ManagementFailure

    /** An installation step failed. */
    public data class Install(val reason: InstallFailureReason) : ManagementFailure

    /** Signing in failed; [terminalCommand] signs in from a terminal instead, when that is possible. */
    public data class Login(val reason: LoginFailureReason, val terminalCommand: String? = null) : ManagementFailure {
        override fun toString(): String = "Login(reason=$reason, hasCommand=${terminalCommand != null})"
    }
}

/** Installation failures. */
public enum class InstallFailureReason {
    /** No release was published. */
    NoRelease,

    /** The release has no build for this platform. */
    NoAssetForPlatform,

    /** The download left the publisher's trusted hosts or was not https. */
    UntrustedSource,

    /** The publisher's checksum does not match the download. */
    ChecksumMismatch,

    /** The download is larger or smaller than announced. */
    SizeMismatch,

    /** The download exceeds the size limit. */
    TooLarge,

    /** The archive is damaged or contains unsafe entries. */
    InvalidArchive,

    /** The archive does not contain the executable. */
    ExecutableMissing,

    /** The new copy reports another version than the release. */
    VersionMismatch,

    /** Not enough free disk space. */
    NotEnoughSpace,

    /** Files are used by a running process. */
    FilesInUse,

    /** The publisher limits requests for now; retry later. */
    RateLimited,

    /** The publisher could not be reached. */
    Network,

    /** This engine or platform cannot be installed by Heartbeat. */
    Unsupported,
}

/** Sign-in failures. */
public enum class LoginFailureReason {
    /** Nobody completed the sign-in in time. */
    TimedOut,

    /** The provider rejected the sign-in. */
    Rejected,

    /** The CLI can sign in only from a terminal. */
    RequiresTerminal,

    /** The CLI offered a sign-in page outside the provider's hosts. */
    UntrustedUrl,

    /** The engine has no CLI sign-in. */
    Unsupported,
}

/**
 * Actions that apply to the engine now. A running job allows none (it is cancelled instead); engines that a flag or
 * the platform switches off allow only launch settings, which never start a process.
 */
public fun ManagedEngine.actions(): Set<EngineActionKind> {
    if (job?.phase?.isFinished == false) return emptySet()
    val configure = if (spec.launch.options.isEmpty()) emptySet() else setOf(EngineActionKind.Configure)
    val isRunnable = enablement.reasons.all { it == DisabledReason.DisabledByUser }
    return if (isRunnable) configure + installActions() + loginActions() + runtimeActions() else configure
}

private fun ManagedEngine.installActions(): Set<EngineActionKind> = buildSet {
    add(EngineActionKind.Inspect)
    if (spec.install == InstallSupport.BuiltIn) return@buildSet
    add(EngineActionKind.CheckForUpdates)
    val managed = installation.managed
    if (spec.install == InstallSupport.Managed && managed == null) add(EngineActionKind.Install)
    val canReplace = spec.install == InstallSupport.Bundled || managed != null
    if (canReplace && installation.isUpdateAvailable) add(EngineActionKind.Update)
    if (managed != null) add(EngineActionKind.Uninstall)
}

private fun ManagedEngine.loginActions(): Set<EngineActionKind> {
    if (spec.login != LoginSupport.Cli && spec.login != LoginSupport.CliWithDeviceCode) return emptySet()
    if (login is LoginState.SignedIn) return setOf(EngineActionKind.Logout)
    val current = installation.current
    val hasExecutable = current != null && current.source != InstallSource.Missing && current.isRunnable
    return if (hasExecutable) setOf(EngineActionKind.Login) else emptySet()
}

private fun ManagedEngine.runtimeActions(): Set<EngineActionKind> =
    if (runtime.canRestart) setOf(EngineActionKind.Restart) else emptySet()

/**
 * Whether [candidate] is a newer version than [installed]: dot-separated numeric segments compare numerically, and a
 * pre-release (`1.2.0-beta`) is older than its release. Unparsable versions are never newer.
 */
public fun isNewerVersion(candidate: String, installed: String): Boolean {
    val next = parseVersion(candidate) ?: return false
    val current = parseVersion(installed) ?: return candidate != installed
    val size = maxOf(next.numbers.size, current.numbers.size)
    for (index in 0 until size) {
        val left = next.numbers.getOrElse(index) { 0 }
        val right = current.numbers.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return !next.isPreRelease && current.isPreRelease
}

private class ParsedVersion(val numbers: List<Long>, val isPreRelease: Boolean)

private val VersionPattern = Regex("""v?(\d+(?:\.\d+)*)(-[^+]+)?(\+.+)?""")

private fun parseVersion(value: String): ParsedVersion? {
    val match = VersionPattern.matchEntire(value.trim()) ?: return null
    val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
    return ParsedVersion(numbers, isPreRelease = match.groupValues[2].isNotEmpty())
}
