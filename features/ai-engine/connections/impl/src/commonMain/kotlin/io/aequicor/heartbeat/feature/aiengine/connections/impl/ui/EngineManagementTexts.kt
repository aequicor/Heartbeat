package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.runtime.Composable
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.DisabledReasonUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineActionUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EnginePanelUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.InstallFailureUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.InstallSourceUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.InstallSupportUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.InstallationUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.JobPhaseUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.LoginFailureUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ManagementFailureUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.RuntimeUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_bundled_version
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_logout
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_logout_body
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_logout_title
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_revert
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_revert_body
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_revert_title
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_uninstall
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_uninstall_body
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_uninstall_title
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_update
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_update_body
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_confirm_update_title
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_checksum
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_executable
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_files_in_use
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_invalid_archive
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_network
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_no_asset
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_no_release
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_rate_limited
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_size
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_space
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_storage
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_too_large
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_unsupported
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_untrusted
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_install_failure_version
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_job_install
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_job_login
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_job_logout
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_job_uninstall
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_job_update
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_failure_rejected
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_failure_terminal
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_failure_timed_out
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_failure_unsupported
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_failure_untrusted
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_not_inspected
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_activating
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_browser
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_cancelled
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_checking
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_code
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_downloading
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_downloading_unknown
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_preparing
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_removing
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_succeeded
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_unpacking
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_phase_verifying
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_reason_catalog
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_reason_flag
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_reason_platform
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_reason_user
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_runtime_running
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_runtime_stopped
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_size_gb
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_size_kb
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_size_mb
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_source_builtin
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_source_bundled
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_source_custom
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_source_managed
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_source_missing
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_source_system
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_version
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.reflect.KClass

@Composable
internal fun reasonText(reason: DisabledReasonUi): String = when (reason.kind) {
    DisabledReasonUi.Kind.UnsupportedPlatform -> stringResource(Res.string.engine_reason_platform)
    DisabledReasonUi.Kind.CatalogFlagOff -> stringResource(Res.string.engine_reason_catalog, reason.flag.orEmpty())
    DisabledReasonUi.Kind.EngineFlagOff -> stringResource(Res.string.engine_reason_flag, reason.flag.orEmpty())
    DisabledReasonUi.Kind.DisabledByUser -> stringResource(Res.string.engine_reason_user)
}

/** Source, version and the versions that could replace it, e.g. "Installed by Heartbeat · version 0.2.0". */
@Composable
internal fun installationSummary(installation: InstallationUi): String {
    val source = installation.source ?: return stringResource(Res.string.engine_not_inspected)
    return listOfNotNull(
        stringResource(sourceText(source)),
        installation.version?.let { stringResource(Res.string.engine_version, it) },
        installation.bundledVersion
            ?.takeIf { installation.support == InstallSupportUi.Bundled && source != InstallSourceUi.Bundled }
            ?.let { stringResource(Res.string.engine_bundled_version, it) },
    ).joinToString(" · ")
}

@Composable
internal fun runtimeSummary(runtime: RuntimeUi): String = if (runtime.runtimes == 0) {
    stringResource(Res.string.engine_runtime_stopped)
} else {
    stringResource(Res.string.engine_runtime_running, runtime.runtimes, runtime.openSessions, runtime.activeTurns)
}

@Composable
internal fun jobTitle(action: EngineActionUi): String = stringResource(
    when (action) {
        EngineActionUi.Install -> Res.string.engine_job_install
        EngineActionUi.Update -> Res.string.engine_job_update
        EngineActionUi.Uninstall -> Res.string.engine_job_uninstall
        EngineActionUi.Login -> Res.string.engine_job_login
        EngineActionUi.Logout -> Res.string.engine_job_logout
    },
)

@Composable
internal fun phaseText(phase: JobPhaseUi): String = when (phase) {
    is JobPhaseUi.Downloading -> phase.total?.let {
        stringResource(Res.string.engine_phase_downloading, sizeText(phase.bytes), sizeText(it))
    } ?: stringResource(Res.string.engine_phase_downloading_unknown, sizeText(phase.bytes))

    is JobPhaseUi.Failed -> failureText(phase.failure)

    is JobPhaseUi.AwaitingBrowser, is JobPhaseUi.AwaitingCode, JobPhaseUi.Preparing, JobPhaseUi.Verifying,
    JobPhaseUi.Unpacking, JobPhaseUi.Checking, JobPhaseUi.Activating, JobPhaseUi.Removing, JobPhaseUi.Succeeded,
    JobPhaseUi.Cancelled,
    -> stringResource(PhaseTexts.getValue(phase::class))
}

@Composable
internal fun failureText(failure: ManagementFailureUi): String = when (failure) {
    is ManagementFailureUi.Engine -> stringResource(failure.failure.message())
    is ManagementFailureUi.Install -> stringResource(installFailureText(failure.reason))
    is ManagementFailureUi.Login -> stringResource(loginFailureText(failure.reason))
}

/** Title, body and confirm label of the confirmation of [action]; null for actions that start without one. */
@Composable
internal fun confirmationTexts(action: EngineActionUi, panel: EnginePanelUi): Triple<String, String, String>? {
    val isBundled = panel.installation.support == InstallSupportUi.Bundled
    return when (action) {
        EngineActionUi.Uninstall -> if (isBundled) {
            Triple(
                stringResource(Res.string.engine_confirm_revert_title),
                stringResource(Res.string.engine_confirm_revert_body),
                stringResource(Res.string.engine_confirm_revert),
            )
        } else {
            Triple(
                stringResource(Res.string.engine_confirm_uninstall_title),
                stringResource(Res.string.engine_confirm_uninstall_body),
                stringResource(Res.string.engine_confirm_uninstall),
            )
        }

        EngineActionUi.Update -> Triple(
            stringResource(Res.string.engine_confirm_update_title),
            stringResource(Res.string.engine_confirm_update_body, panel.installation.latest.orEmpty()),
            stringResource(Res.string.engine_confirm_update),
        )

        EngineActionUi.Logout -> Triple(
            stringResource(Res.string.engine_confirm_logout_title),
            stringResource(Res.string.engine_confirm_logout_body),
            stringResource(Res.string.engine_confirm_logout),
        )

        EngineActionUi.Install, EngineActionUi.Login -> null
    }
}

/** A size in KB, MB or GB with one decimal, e.g. "12.5 MB"; the resource places the decimal separator. */
@Composable
internal fun sizeText(bytes: Long): String {
    val (unit, value) = scaledSize(bytes)
    val units = listOf(Res.string.engine_size_kb, Res.string.engine_size_mb, Res.string.engine_size_gb)
    return stringResource(units[unit], value.first, value.second)
}

/** [bytes] in the largest of KB, MB and GB that keeps the value at or above one: the unit and (whole, tenths). */
internal fun scaledSize(bytes: Long): Pair<Int, Pair<Long, Long>> {
    var value = bytes.toDouble() / KILOBYTE
    var unit = 0
    while (value >= KILOBYTE && unit < LAST_SIZE_UNIT) {
        value /= KILOBYTE
        unit++
    }
    val tenths = (value * TENTHS).toLong()
    return unit to (tenths / TENTHS to tenths % TENTHS)
}

private fun sourceText(source: InstallSourceUi): StringResource = when (source) {
    InstallSourceUi.BuiltIn -> Res.string.engine_source_builtin
    InstallSourceUi.Bundled -> Res.string.engine_source_bundled
    InstallSourceUi.Managed -> Res.string.engine_source_managed
    InstallSourceUi.System -> Res.string.engine_source_system
    InstallSourceUi.Custom -> Res.string.engine_source_custom
    InstallSourceUi.Missing -> Res.string.engine_source_missing
}

private fun installFailureText(reason: InstallFailureUi): StringResource = InstallFailureTexts.getValue(reason)

private fun loginFailureText(reason: LoginFailureUi): StringResource = when (reason) {
    LoginFailureUi.TimedOut -> Res.string.engine_login_failure_timed_out
    LoginFailureUi.Rejected -> Res.string.engine_login_failure_rejected
    LoginFailureUi.RequiresTerminal -> Res.string.engine_login_failure_terminal
    LoginFailureUi.UntrustedUrl -> Res.string.engine_login_failure_untrusted
    LoginFailureUi.Unsupported -> Res.string.engine_login_failure_unsupported
}

/** Text of each phase without parameters; the panel tests check that every phase has one. */
internal val PhaseTexts: Map<KClass<out JobPhaseUi>, StringResource> = mapOf(
    JobPhaseUi.Preparing::class to Res.string.engine_phase_preparing,
    JobPhaseUi.Verifying::class to Res.string.engine_phase_verifying,
    JobPhaseUi.Unpacking::class to Res.string.engine_phase_unpacking,
    JobPhaseUi.Checking::class to Res.string.engine_phase_checking,
    JobPhaseUi.Activating::class to Res.string.engine_phase_activating,
    JobPhaseUi.Removing::class to Res.string.engine_phase_removing,
    JobPhaseUi.AwaitingBrowser::class to Res.string.engine_phase_browser,
    JobPhaseUi.AwaitingCode::class to Res.string.engine_phase_code,
    JobPhaseUi.Succeeded::class to Res.string.engine_phase_succeeded,
    JobPhaseUi.Cancelled::class to Res.string.engine_phase_cancelled,
)

/** Message of each installation failure; the panel tests check that every reason has one. */
internal val InstallFailureTexts: Map<InstallFailureUi, StringResource> = mapOf(
    InstallFailureUi.NoRelease to Res.string.engine_install_failure_no_release,
    InstallFailureUi.NoAssetForPlatform to Res.string.engine_install_failure_no_asset,
    InstallFailureUi.UntrustedSource to Res.string.engine_install_failure_untrusted,
    InstallFailureUi.ChecksumMismatch to Res.string.engine_install_failure_checksum,
    InstallFailureUi.SizeMismatch to Res.string.engine_install_failure_size,
    InstallFailureUi.TooLarge to Res.string.engine_install_failure_too_large,
    InstallFailureUi.InvalidArchive to Res.string.engine_install_failure_invalid_archive,
    InstallFailureUi.ExecutableMissing to Res.string.engine_install_failure_executable,
    InstallFailureUi.VersionMismatch to Res.string.engine_install_failure_version,
    InstallFailureUi.NotEnoughSpace to Res.string.engine_install_failure_space,
    InstallFailureUi.FilesInUse to Res.string.engine_install_failure_files_in_use,
    InstallFailureUi.Storage to Res.string.engine_install_failure_storage,
    InstallFailureUi.RateLimited to Res.string.engine_install_failure_rate_limited,
    InstallFailureUi.Network to Res.string.engine_install_failure_network,
    InstallFailureUi.Unsupported to Res.string.engine_install_failure_unsupported,
)

private const val KILOBYTE = 1024.0
private const val LAST_SIZE_UNIT = 2
private const val TENTHS = 10
