package io.aequicor.heartbeat.feature.appupdate.impl.domain

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateEffect
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent.Internal

/**
 * IO of the update machine: what this launch is, the newest release and the installation. Expected failures become
 * the machine's failure intents; anything else fails the effect, which the spec maps the same way.
 */
internal class AppUpdateEffects(
    private val probe: InstallationProbe,
    private val releases: AppReleaseSource,
    private val installer: UpdateInstaller,
) : EffectHandler<AppUpdateEffect, AppUpdateIntent> {
    private val log = Log.tag("AppUpdateEffects")

    override suspend fun handle(effect: AppUpdateEffect, machine: EffectScope<AppUpdateIntent>) {
        when (effect) {
            AppUpdateEffect.FetchLatest -> fetchLatest(machine)
            is AppUpdateEffect.Install -> install(effect.release, machine)
        }
    }

    private suspend fun fetchLatest(machine: EffectScope<AppUpdateIntent>) {
        val installation = when (val check = probe.current()) {
            is InstallationCheck.Unsupported -> {
                log.i { "updates unavailable reason=${check.reason}" }
                machine.send(Internal.Unsupported(check.reason))
                return
            }

            is InstallationCheck.Supported -> check.installation
        }
        val previous = installer.previousAttempt(installation.version)
        val intent = try {
            val release = releases.newer(installation.repository, installation.version, installation.platform)
            Internal.Checked(
                release,
                AppUpdateFailure.PreviousAttemptFailed.takeIf { previous == PreviousAttempt.Failed },
            )
        } catch (e: AppUpdateException) {
            log.w(e) { "update check failed failure=${e.failure}" }
            Internal.CheckFailed(e.failure)
        }
        machine.send(intent)
    }

    private suspend fun install(release: AppRelease, machine: EffectScope<AppUpdateIntent>) {
        val installation = when (val check = probe.current()) {
            is InstallationCheck.Unsupported -> {
                log.w { "installation refused: updates unavailable reason=${check.reason}" }
                machine.send(Internal.InstallFailed(AppUpdateFailure.InstallFailed))
                return
            }

            is InstallationCheck.Supported -> check.installation
        }
        log.i { "install update version=${release.version} installed=${installation.version}" }
        val progress = object : InstallProgressSink {
            private var reported = -1L

            // Chunks arrive many times a second: the machine hears of a new percent (or MiB, size unknown) only.
            @HighFrequency
            override suspend fun downloading(received: Long, total: Long?) {
                val step = if (total != null && total > 0) received * PERCENT / total else received / MEBIBYTE
                if (step == reported) return
                reported = step
                machine.send(Internal.Progress(received, total))
            }

            override suspend fun preparing() {
                machine.send(Internal.Preparing)
            }
        }
        val intent = try {
            installer.install(installation, release, progress)
            log.i { "update installer started version=${release.version}" }
            Internal.Launched
        } catch (e: AppUpdateException) {
            log.w(e) { "update installation failed version=${release.version} failure=${e.failure}" }
            Internal.InstallFailed(e.failure)
        }
        machine.send(intent)
    }
}

private const val PERCENT = 100L
private const val MEBIBYTE = 1L shl 20
