package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A newer release of the app offered by the desktop update (`app-update`); [pageUrl] is its release page. */
@Serializable
@SerialName("AppUpdateUi")
@Immutable
data class AppUpdateUi(
    val version: String,
    val stage: AppUpdateStageUi,
    val percent: Int? = null,
    val failure: AppUpdateFailureUi? = null,
    val pageUrl: String = "",
)

/** What the update button does now. */
@Serializable
@SerialName("AppUpdateStageUi")
enum class AppUpdateStageUi {
    /** Installs the release and restarts; after a [AppUpdateUi.failure] it retries. */
    @SerialName("Ready")
    Ready,

    /** The app cannot replace itself: the button opens the release page. */
    @SerialName("ManualInstall")
    ManualInstall,

    /** Downloading the release. */
    @SerialName("Downloading")
    Downloading,

    /** Verifying and unpacking the download, starting the installer. */
    @SerialName("Preparing")
    Preparing,

    /** The app quits so that the installer can replace it. */
    @SerialName("Restarting")
    Restarting,
}

/** Why the last check or installation ended, shown in the button's tooltip. */
@Serializable
@SerialName("AppUpdateFailureUi")
enum class AppUpdateFailureUi {
    @SerialName("Network")
    Network,

    @SerialName("RateLimited")
    RateLimited,

    @SerialName("InvalidRelease")
    InvalidRelease,

    @SerialName("Corrupted")
    Corrupted,

    @SerialName("NotEnoughSpace")
    NotEnoughSpace,

    @SerialName("Storage")
    Storage,

    @SerialName("InstallFailed")
    InstallFailed,

    @SerialName("PreviousAttemptFailed")
    PreviousAttemptFailed,
}

/** Restarting interrupts ordinary turns and every active organism session, including chats outside the panes. */
fun AiStudioScreenState.hasRunningAgentsForUpdate(): Boolean = running.isNotEmpty() || organisms.values.any {
    it.status != OrganismStatusUi.Completed && it.status != OrganismStatusUi.Aborted && it.subSessions.any { session ->
        session.state in setOf(
            SubSessionStateUi.Germinating,
            SubSessionStateUi.Working,
            SubSessionStateUi.AwaitingUser,
            SubSessionStateUi.Judging,
        )
    }
}
