package io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigOverride
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineJob
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.JobPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.actions
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateLaunchSettings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

/** Why an engine is off; [flag] names the developer flag that keeps it off. */
@Immutable
data class DisabledReasonUi(val kind: Kind, val flag: String? = null) {
    /** Reason kinds, in the order the panel lists them. */
    enum class Kind { UnsupportedPlatform, CatalogFlagOff, EngineFlagOff, DisabledByUser }
}

/** How an engine reaches the device. */
enum class InstallSupportUi { BuiltIn, Managed, Bundled }

/** Where the executable an engine runs comes from. */
enum class InstallSourceUi { BuiltIn, Bundled, Managed, System, Custom, Missing }

/** Whether a version was verified with this Heartbeat build. */
enum class CompatibilityUi { Verified, Unverified, Unknown }

/** Installation step failures, one message each. */
enum class InstallFailureUi {
    NoRelease,
    NoAssetForPlatform,
    UntrustedSource,
    ChecksumMismatch,
    SizeMismatch,
    TooLarge,
    InvalidArchive,
    ExecutableMissing,
    VersionMismatch,
    NotEnoughSpace,
    FilesInUse,
    Storage,
    RateLimited,
    Network,
    Unsupported,
}

/** Sign-in failures, one message each. */
enum class LoginFailureUi { TimedOut, Rejected, RequiresTerminal, UntrustedUrl, Unsupported }

/** A failed management step; [terminalCommand] signs in from a terminal instead. */
@Immutable
sealed interface ManagementFailureUi {
    /** The engine or its environment failed. */
    data class Engine(val failure: FailureUi) : ManagementFailureUi

    /** An installation step failed. */
    data class Install(val reason: InstallFailureUi) : ManagementFailureUi

    /** Signing in failed. */
    data class Login(val reason: LoginFailureUi, val terminalCommand: String? = null) : ManagementFailureUi
}

/** What the engine runs now and what may replace it; [source] is null until inspected. */
@Immutable
data class InstallationUi(
    val support: InstallSupportUi,
    val source: InstallSourceUi? = null,
    val version: String? = null,
    val path: String? = null,
    val isRunnable: Boolean = true,
    val managedVersion: String? = null,
    val bundledVersion: String? = null,
    val latest: String? = null,
    val isUpdateAvailable: Boolean = false,
    val compatibility: CompatibilityUi = CompatibilityUi.Unknown,
    val isChecked: Boolean = false,
    val failure: ManagementFailureUi? = null,
)

/** CLI sign-in state. */
@Immutable
sealed interface LoginUi {
    /** Not checked yet. */
    data object Unknown : LoginUi

    /** The CLI is signed out. */
    data object SignedOut : LoginUi

    /** The CLI is signed in, as [account] when known. */
    data class SignedIn(val account: String?) : LoginUi {
        override fun toString(): String = "SignedIn(***)"
    }
}

/** How a CLI may sign in. */
enum class LoginMethodUi { Browser, DeviceCode }

/** Runtimes of the engine in this profile. */
@Immutable
data class RuntimeUi(
    val runtimes: Int = 0,
    val openSessions: Int = 0,
    val activeTurns: Int = 0,
    val isStale: Boolean = false,
    val hasExited: Boolean = false,
)

/** A long operation of an engine. */
enum class EngineActionUi { Install, Update, Uninstall, Login, Logout }

/** Buttons the panel enables now. */
enum class EngineActionKindUi {
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

/** Progress of a job; [progress] is the downloaded share when the size is known. */
@Immutable
sealed interface JobPhaseUi {
    /** Resolving or preparing. */
    data object Preparing : JobPhaseUi

    /** Downloading [bytes] of [total]. */
    data class Downloading(val bytes: Long, val total: Long?) : JobPhaseUi {
        val progress: Float? get() = total?.takeIf { it > 0 }?.let {
            (bytes.toDouble() / it).toFloat().coerceIn(
                0f,
                1f,
            )
        }
    }

    /** Checking the checksum. */
    data object Verifying : JobPhaseUi

    /** Unpacking. */
    data object Unpacking : JobPhaseUi

    /** Starting the new copy once. */
    data object Checking : JobPhaseUi

    /** Switching to the new copy. */
    data object Activating : JobPhaseUi

    /** Removing Heartbeat's copy. */
    data object Removing : JobPhaseUi

    /** The user signs in at [url]; with a device code, [userCode] is entered there. */
    data class AwaitingBrowser(val url: String, val userCode: String?) : JobPhaseUi {
        override fun toString(): String = "AwaitingBrowser(***)"
    }

    /** The CLI waits for the code the page at [url] shows. */
    data class AwaitingCode(val url: String?) : JobPhaseUi {
        override fun toString(): String = "AwaitingCode(***)"
    }

    /** Completed. */
    data object Succeeded : JobPhaseUi

    /** Failed with [failure]. */
    data class Failed(val failure: ManagementFailureUi) : JobPhaseUi

    /** Cancelled. */
    data object Cancelled : JobPhaseUi
}

/** A running job, or the outcome of the last one until dismissed. */
@Immutable
data class JobUi(val action: EngineActionUi, val phase: JobPhaseUi) {
    val isRunning: Boolean
        get() = phase !is JobPhaseUi.Succeeded && phase !is JobPhaseUi.Failed && phase !is JobPhaseUi.Cancelled
}

/** Launch settings an engine accepts. */
enum class LaunchOptionUi { Executable, HomeDirectory, ConfigOverrides, Environment }

/** Why a launch setting is rejected or questionable. */
enum class LaunchProblemReasonUi {
    Unsupported,
    NotAbsolute,
    NotExe,
    ScriptWrapper,
    InvalidName,
    InvalidKey,
    Reserved,
    Secret,
    Duplicate,
    TooLong,
    ControlCharacter,
    NotFound,
    NotExecutable,
    NotADirectory,
}

/** A problem of one setting; [index] points into a list setting. */
@Immutable
data class LaunchProblemUi(val option: LaunchOptionUi, val reason: LaunchProblemReasonUi, val index: Int? = null)

/** One `key=value` row of an editable list. */
@Immutable
data class KeyValueUi(val key: String = "", val value: String = "") {
    override fun toString(): String = "KeyValueUi(***)"
}

/** Editable launch settings; blank fields keep the engine's defaults. Values are user paths and never logged. */
@Immutable
data class LaunchDraftUi(
    val executable: String = "",
    val homeDirectory: String = "",
    val configOverrides: ImmutableList<KeyValueUi> = persistentListOf(),
    val environment: ImmutableList<KeyValueUi> = persistentListOf(),
) {
    override fun toString(): String = "LaunchDraftUi(***)"
}

/** Launch settings of the engine: the saved ones, the draft being edited and what is wrong with each. */
@Immutable
data class LaunchUi(
    val options: ImmutableSet<LaunchOptionUi>,
    val homeVariable: String?,
    val draft: LaunchDraftUi,
    val isEditing: Boolean,
    val isDirty: Boolean,
    /** The saved settings differ from the engine's defaults. */
    val isCustomized: Boolean,
    /** Errors of the draft; saving is disabled while there are any. */
    val errors: ImmutableList<LaunchProblemUi>,
    /** File checks of the saved settings; they never block saving. */
    val warnings: ImmutableList<LaunchProblemUi>,
)

/** Management panel of the selected engine; null while engine management is off. */
@Immutable
data class EnginePanelUi(
    val id: String,
    val title: String,
    val isEnabled: Boolean,
    val isUserEnabled: Boolean,
    /** The profile switch may turn the engine on or off (developer flags and the platform still apply). */
    val isSwitchable: Boolean,
    val reasons: ImmutableList<DisabledReasonUi>,
    val installation: InstallationUi,
    val login: LoginUi?,
    val loginMethods: ImmutableList<LoginMethodUi>,
    val launch: LaunchUi?,
    val runtime: RuntimeUi,
    val job: JobUi?,
    val actions: ImmutableSet<EngineActionKindUi>,
)

/** The panel of [engine] with the local launch [draft] (null: not editing). */
internal fun ManagedEngine.toPanel(draft: LaunchDraftUi?, platform: EnginePlatform?): EnginePanelUi {
    val saved = launch.settings.toDraft()
    val edited = draft ?: saved
    val launchUi = if (spec.launch.options.isEmpty()) {
        null
    } else {
        LaunchUi(
            options = spec.launch.options.map { LaunchOptionUi.valueOf(it.name) }.toImmutableSet(),
            homeVariable = spec.launch.homeVariable,
            draft = edited,
            isEditing = draft != null,
            isDirty = edited.toSettings() != launch.settings,
            isCustomized = !launch.settings.isDefault,
            errors = validateLaunchSettings(edited.toSettings(), spec.launch, platform).map { problem ->
                val rows = when (problem.option) {
                    LaunchOption.ConfigOverrides -> edited.configOverrides
                    LaunchOption.Environment -> edited.environment
                    LaunchOption.Executable, LaunchOption.HomeDirectory -> emptyList()
                }
                val indices = rows.indices.filter { !rows[it].isBlank() }
                problem.copy(index = problem.index?.let { indices[it] })
            }.toUi(),
            warnings = launch.warnings.toUi(),
        )
    }
    return EnginePanelUi(
        id = descriptor.id.value,
        title = descriptor.title,
        isEnabled = enablement.isEnabled,
        isUserEnabled = enablement.isUserEnabled,
        isSwitchable = DisabledReason.UnsupportedPlatform !in enablement.reasons,
        reasons = enablement.reasons.map { it.toUi(descriptor.toggle.key) }.sortedBy { it.kind }.toImmutableList(),
        installation = installation.toUi(spec.install),
        login = login.toUi(),
        loginMethods = when (spec.login) {
            LoginSupport.Cli -> persistentListOf(LoginMethodUi.Browser)
            LoginSupport.CliWithDeviceCode -> persistentListOf(LoginMethodUi.Browser, LoginMethodUi.DeviceCode)
            LoginSupport.None, LoginSupport.Connections -> persistentListOf()
        },
        launch = launchUi,
        runtime = RuntimeUi(
            runtime.runtimes,
            runtime.openSessions,
            runtime.activeTurns,
            runtime.isStale,
            runtime.hasExited,
        ),
        job = job?.toUi(),
        actions = actions().map { EngineActionKindUi.valueOf(it.name) }.toImmutableSet(),
    )
}

/** Settings of [this] draft: blank values keep defaults, rows without a key and a value are dropped. */
internal fun LaunchDraftUi.toSettings(): LaunchSettings = LaunchSettings(
    executable = executable.trim().ifEmpty { null },
    homeDirectory = homeDirectory.trim().ifEmpty { null },
    configOverrides = configOverrides.filterNot { it.isBlank() }.map { ConfigOverride(it.key.trim(), it.value) },
    environment = environment.filterNot { it.isBlank() }.map { EnvironmentEntry(it.key.trim(), it.value) },
)

internal fun LaunchSettings.toDraft(): LaunchDraftUi = LaunchDraftUi(
    executable = executable.orEmpty(),
    homeDirectory = homeDirectory.orEmpty(),
    configOverrides = configOverrides.map { KeyValueUi(it.key, it.value) }.toImmutableList(),
    environment = environment.map { KeyValueUi(it.name, it.value) }.toImmutableList(),
)

/** The facade action of a panel [action]; a sign-in uses [method]. */
internal fun EngineActionUi.toAction(method: LoginMethodUi = LoginMethodUi.Browser): EngineAction = when (this) {
    EngineActionUi.Install -> EngineAction.Install
    EngineActionUi.Update -> EngineAction.Update
    EngineActionUi.Uninstall -> EngineAction.Uninstall
    EngineActionUi.Login -> EngineAction.Login(LoginMethod.valueOf(method.name))
    EngineActionUi.Logout -> EngineAction.Logout
}

private fun KeyValueUi.isBlank(): Boolean = key.isBlank() && value.isBlank()

private fun DisabledReason.toUi(engineFlag: String): DisabledReasonUi = when (this) {
    DisabledReason.CatalogFlagOff -> DisabledReasonUi(DisabledReasonUi.Kind.CatalogFlagOff, AiEngines.key)
    DisabledReason.EngineFlagOff -> DisabledReasonUi(DisabledReasonUi.Kind.EngineFlagOff, engineFlag)
    DisabledReason.DisabledByUser -> DisabledReasonUi(DisabledReasonUi.Kind.DisabledByUser)
    DisabledReason.UnsupportedPlatform -> DisabledReasonUi(DisabledReasonUi.Kind.UnsupportedPlatform)
}

private fun InstallationState.toUi(support: InstallSupport): InstallationUi = InstallationUi(
    support = InstallSupportUi.valueOf(support.name),
    source = current?.source?.let { InstallSourceUi.valueOf(it.name) },
    version = current?.version,
    path = current?.path,
    isRunnable = current?.isRunnable ?: true,
    managedVersion = managed?.version,
    bundledVersion = bundledVersion,
    latest = latest,
    isUpdateAvailable = isUpdateAvailable,
    compatibility = CompatibilityUi.valueOf(compatibility.name),
    isChecked = checkedAt != null,
    failure = failure?.toUi(),
)

private fun LoginState.toUi(): LoginUi? = when (this) {
    LoginState.NotApplicable -> null
    LoginState.Unknown -> LoginUi.Unknown
    LoginState.SignedOut -> LoginUi.SignedOut
    is LoginState.SignedIn -> LoginUi.SignedIn(account)
}

private fun EngineJob.toUi(): JobUi = JobUi(
    action = when (action) {
        EngineAction.Install -> EngineActionUi.Install
        EngineAction.Update -> EngineActionUi.Update
        EngineAction.Uninstall -> EngineActionUi.Uninstall
        is EngineAction.Login -> EngineActionUi.Login
        EngineAction.Logout -> EngineActionUi.Logout
    },
    phase = phase.toUi(),
)

private fun JobPhase.toUi(): JobPhaseUi = when (this) {
    JobPhase.Preparing -> JobPhaseUi.Preparing
    is JobPhase.Downloading -> JobPhaseUi.Downloading(bytes, total)
    JobPhase.Verifying -> JobPhaseUi.Verifying
    JobPhase.Unpacking -> JobPhaseUi.Unpacking
    JobPhase.Checking -> JobPhaseUi.Checking
    JobPhase.Activating -> JobPhaseUi.Activating
    JobPhase.Removing -> JobPhaseUi.Removing
    is JobPhase.AwaitingBrowser -> JobPhaseUi.AwaitingBrowser(url, userCode)
    is JobPhase.AwaitingCode -> JobPhaseUi.AwaitingCode(url)
    JobPhase.Succeeded -> JobPhaseUi.Succeeded
    is JobPhase.Failed -> JobPhaseUi.Failed(failure.toUi())
    JobPhase.Cancelled -> JobPhaseUi.Cancelled
}

private fun ManagementFailure.toUi(): ManagementFailureUi = when (this) {
    is ManagementFailure.Engine -> ManagementFailureUi.Engine(failure.toUi())
    is ManagementFailure.Install -> ManagementFailureUi.Install(InstallFailureUi.valueOf(reason.name))
    is ManagementFailure.Login -> ManagementFailureUi.Login(LoginFailureUi.valueOf(reason.name), terminalCommand)
}

private fun List<LaunchProblem>.toUi(): ImmutableList<LaunchProblemUi> = map { problem ->
    LaunchProblemUi(
        LaunchOptionUi.valueOf(problem.option.name),
        LaunchProblemReasonUi.valueOf(problem.reason.name),
        problem.index,
    )
}.toImmutableList()
