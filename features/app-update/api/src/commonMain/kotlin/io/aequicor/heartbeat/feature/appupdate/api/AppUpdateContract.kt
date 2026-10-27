package io.aequicor.heartbeat.feature.appupdate.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineState

/**
 * Self-update of the installed desktop app from its GitHub releases. The machine lives in the app scope for the
 * whole launch; nothing is persisted: a launch starts [Inactive] and the host asks for a check.
 */
public sealed interface AppUpdateState : MachineState {
    /** Nothing is checked: updates are switched off or the host has not asked yet. */
    public data object Inactive : AppUpdateState

    /** This launch cannot replace itself (a development launch, an OS without an installer); fixed for the launch. */
    public data class Unsupported(val reason: UnsupportedReason) : AppUpdateState

    /** Asking for the newest release; [offered] stays available with its existing installation [failure]. */
    public data class Checking(val offered: AppRelease? = null, val failure: AppUpdateFailure? = null) : AppUpdateState

    /**
     * The installed version is the newest release. [isPlatformUpdateRequired]: the beta channel is on, but this launch
     * has no launcher that applies bundle trains, so only a full installer can move it to the beta channel.
     */
    public data class UpToDate(val isPlatformUpdateRequired: Boolean = false) : AppUpdateState

    /** The newest release is unknown; the next check retries. */
    public data class CheckFailed(val failure: AppUpdateFailure) : AppUpdateState

    /** A newer [release] can be installed; [failure] explains a failed attempt or the need for a manual install. */
    public data class Available(val release: AppRelease, val failure: AppUpdateFailure? = null) : AppUpdateState

    /** The user asked to install [release]: it is being downloaded, verified and prepared. */
    public data class Installing(val release: AppRelease, val progress: InstallProgress) : AppUpdateState

    /**
     * The installer waits for this process to end and starts the new version. The host must now quit the app the way
     * the user would; nothing leaves this state.
     */
    public data class Restarting(val release: AppRelease) : AppUpdateState
}

/** Why a launch cannot update itself. */
public enum class UnsupportedReason {
    /** Not an installed release build: a development launch, or a build without an update source. */
    NotReleaseBuild,

    /** The OS or architecture has no release build to install. */
    UnsupportedPlatform,
}

/**
 * The installable build of one release for this machine: the asset [assetName] at the https [url] whose SHA-256 the
 * publisher states as [sha256]; [pageUrl] is the release page for a manual install.
 *
 * A bundle train ([train] is its id, [version] the same id) has no installer: [assetName] is `train-manifest.json` at
 * [url], [sha256] is the digest GitHub states for the manifest or empty, and [size] is null. Integrity is then
 * checked per jar by the SHA-256 the manifest lists.
 */
public data class AppRelease(
    val version: String,
    val assetName: String,
    val url: String,
    val sha256: String,
    val size: Long?,
    val pageUrl: String,
    val train: String? = null,
)

/** How far the installation of a release has come. */
public sealed interface InstallProgress {
    /** [received] bytes of the release file of [total] bytes, when the size is known. */
    public data class Downloading(val received: Long = 0, val total: Long? = null) : InstallProgress

    /** The verified file is unpacked and the installer is started. */
    public data object Preparing : InstallProgress
}

/** Why a check or an installation failed; the user sees it next to the update button. */
public enum class AppUpdateFailure {
    /** GitHub could not be reached or answered with an error. */
    Network,

    /** GitHub limits anonymous requests; a later check succeeds. */
    RateLimited,

    /** The newest release has no build for this OS and architecture, or no checksum for it. */
    InvalidRelease,

    /** The downloaded file differs from the published size or SHA-256. */
    Corrupted,

    /** The disk has no room for the download and its unpacked copy. */
    NotEnoughSpace,

    /** The download or the unpacked copy could not be written. */
    Storage,

    /** System packages and apps in read-only or translocated locations require manual installation. */
    ManualInstallRequired,

    /** The update could not be prepared or the installer could not be started. */
    InstallFailed,

    /** The installation started before the last restart did not finish; this launch still runs the old version. */
    PreviousAttemptFailed,
}

/** Requests of the host and the update button, and results of the update effects. */
public sealed interface AppUpdateIntent : MachineIntent {
    /** Intents accepted through [AppUpdateMachineKey]. */
    public sealed interface Public : AppUpdateIntent {
        /** Looks for a newer release: at launch, periodically and when updates are switched on. */
        public data object Check : Public

        /** The user asked to install the offered release and restart. */
        public data object Install : Public

        /** Updates were switched off: forget the offer. An installation that started is not interrupted. */
        public data object Deactivate : Public
    }

    /** Results of [AppUpdateEffect]s; other features cannot send these. */
    public sealed interface Internal : AppUpdateIntent {
        /** This launch cannot update itself. */
        public data class Unsupported(val reason: UnsupportedReason) : Internal

        /**
         * The newest release is [release] when it is newer than the installed version, null otherwise. [failure] is
         * [AppUpdateFailure.ManualInstallRequired] for system packages or [AppUpdateFailure.PreviousAttemptFailed]
         * when an installation of an earlier launch did not finish.
         */
        public data class Checked(
            val release: AppRelease?,
            val failure: AppUpdateFailure? = null,
            val isPlatformUpdateRequired: Boolean = false,
        ) : Internal

        /** The newest release could not be determined. */
        public data class CheckFailed(val failure: AppUpdateFailure) : Internal

        /** Download progress of the release file. */
        public data class Progress(val received: Long, val total: Long?) : Internal

        /** The file is verified; the update is being prepared. */
        public data object Preparing : Internal

        /** The installer is waiting for this process to end. */
        public data object Launched : Internal

        /** The installation stopped before the installer was started; the app is unchanged. */
        public data class InstallFailed(val failure: AppUpdateFailure) : Internal
    }
}

/** IO of the update, executed by the impl effect handler. */
public sealed interface AppUpdateEffect : MachineEffect {
    /** Determines the installed version and the newest release; also reports the outcome of an earlier attempt. */
    public data object FetchLatest : AppUpdateEffect

    /** Downloads, verifies and prepares [release], then starts the installer that waits for this process to end. */
    public data class Install(val release: AppRelease) : AppUpdateEffect
}

/** The update has no one-shot events: [AppUpdateState] tells the host and the button everything. */
public sealed interface AppUpdateOutput : MachineOutput

/** Address of the app-scope update machine; it runs only on desktop hosts that start it. */
public object AppUpdateMachineKey : MachineKey<
    AppUpdateState,
    AppUpdateIntent,
    AppUpdateIntent.Public,
    AppUpdateEffect,
    AppUpdateOutput,
> {
    override val name: String = "app-update"
}

/** Checks GitHub releases for a newer version of the installed desktop app and offers to install it. */
public val AppUpdateEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "app_update.enabled",
    description = "Проверка и установка обновлений приложения (Desktop)",
    default = true,
)

/**
 * Opt-in `beta` update channel: besides full releases the check also offers the newest bundle train, a GitHub
 * pre-release that carries `train-manifest.json`. Off by default: stable ignores drafts and pre-releases.
 */
public val AppUpdateBetaChannel: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "app_update.beta",
    description = "Бета-канал обновлений: предварительные релизы с поездами бандлов (Desktop)",
    default = false,
)
