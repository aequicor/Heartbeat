package io.aequicor.heartbeat.feature.appupdate.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent.Internal
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent.Public
import kotlin.test.Test
import kotlin.test.assertEquals

class AppUpdateMachineTest {
    private val release = AppRelease(
        version = "1.4.0",
        assetName = "Heartbeat-1.4.0-macos-arm64.dmg",
        url = "https://github.com/aequicor/Heartbeat/releases/download/v1.4.0/Heartbeat-1.4.0-macos-arm64.dmg",
        sha256 = "a".repeat(64),
        size = 1_000,
        pageUrl = "https://github.com/aequicor/Heartbeat/releases/tag/v1.4.0",
    )
    private val downloading = AppUpdateState.Installing(release, InstallProgress.Downloading(total = 1_000))

    @Test
    fun `check from every idle state fetches the newest release`() {
        listOf(AppUpdateState.Inactive, AppUpdateState.UpToDate, AppUpdateState.CheckFailed(AppUpdateFailure.Network))
            .forEach { idle ->
                AppUpdateMachineSpec.assertTransition(
                    from = idle,
                    intent = Public.Check,
                    to = AppUpdateState.Checking(),
                    effects = listOf(AppUpdateEffect.FetchLatest),
                )
            }
    }

    @Test
    fun `a recheck keeps the offered release installable`() {
        AppUpdateMachineSpec.assertTransition(
            from = AppUpdateState.Available(release, AppUpdateFailure.Corrupted),
            intent = Public.Check,
            to = AppUpdateState.Checking(offered = release),
            effects = listOf(AppUpdateEffect.FetchLatest),
        )
        AppUpdateMachineSpec.assertTransition(
            from = AppUpdateState.Checking(offered = release),
            intent = Public.Install,
            to = downloading,
            effects = listOf(AppUpdateEffect.Install(release)),
        )
    }

    @Test
    fun `a newer release is offered with the outcome of the previous attempt`() {
        AppUpdateMachineSpec.assertTransition(
            from = AppUpdateState.Checking(),
            intent = Internal.Checked(release, AppUpdateFailure.PreviousAttemptFailed),
            to = AppUpdateState.Available(release, AppUpdateFailure.PreviousAttemptFailed),
        )
    }

    @Test
    fun `no newer release means up to date, also dropping an earlier offer`() {
        AppUpdateMachineSpec.assertTransition(
            from = AppUpdateState.Checking(offered = release),
            intent = Internal.Checked(null),
            to = AppUpdateState.UpToDate,
        )
    }

    @Test
    fun `a failed check keeps an offered release and reports nothing otherwise`() {
        AppUpdateMachineSpec.assertTransition(
            from = AppUpdateState.Checking(offered = release),
            intent = Internal.CheckFailed(AppUpdateFailure.RateLimited),
            to = AppUpdateState.Available(release),
        )
        AppUpdateMachineSpec.assertTransition(
            from = AppUpdateState.Checking(),
            intent = Internal.CheckFailed(AppUpdateFailure.RateLimited),
            to = AppUpdateState.CheckFailed(AppUpdateFailure.RateLimited),
        )
    }

    @Test
    fun `an unsupported launch stays unsupported until updates are switched off`() {
        AppUpdateMachineSpec.assertTransition(
            from = AppUpdateState.Checking(),
            intent = Internal.Unsupported(UnsupportedReason.NotReleaseBuild),
            to = AppUpdateState.Unsupported(UnsupportedReason.NotReleaseBuild),
        )
        AppUpdateMachineSpec.assertIgnored(AppUpdateState.Unsupported(UnsupportedReason.NotReleaseBuild), Public.Check)
        AppUpdateMachineSpec.assertTransition(
            from = AppUpdateState.Unsupported(UnsupportedReason.UnsupportedPlatform),
            intent = Public.Deactivate,
            to = AppUpdateState.Inactive,
        )
    }

    @Test
    fun `install downloads the offered release, also as a retry`() {
        AppUpdateMachineSpec.assertTransition(
            from = AppUpdateState.Available(release, AppUpdateFailure.Network),
            intent = Public.Install,
            to = downloading,
            effects = listOf(AppUpdateEffect.Install(release)),
        )
    }

    @Test
    fun `install needs an offered release`() {
        AppUpdateMachineSpec.assertIgnored(AppUpdateState.Checking(), Public.Install)
        AppUpdateMachineSpec.assertIgnored(AppUpdateState.UpToDate, Public.Install)
        AppUpdateMachineSpec.assertIgnored(AppUpdateState.Inactive, Public.Install)
    }

    @Test
    fun `progress and preparation stay in the installation`() {
        AppUpdateMachineSpec.assertTransition(
            from = downloading,
            intent = Internal.Progress(500, 1_000),
            to = AppUpdateState.Installing(release, InstallProgress.Downloading(500, 1_000)),
        )
        AppUpdateMachineSpec.assertTransition(
            from = downloading,
            intent = Internal.Preparing,
            to = AppUpdateState.Installing(release, InstallProgress.Preparing),
        )
    }

    @Test
    fun `a started installer restarts the app`() {
        AppUpdateMachineSpec.assertTransition(
            from = AppUpdateState.Installing(release, InstallProgress.Preparing),
            intent = Internal.Launched,
            to = AppUpdateState.Restarting(release),
        )
    }

    @Test
    fun `a failed installation offers the release again with its failure`() {
        AppUpdateMachineSpec.assertTransition(
            from = downloading,
            intent = Internal.InstallFailed(AppUpdateFailure.ManualInstallRequired),
            to = AppUpdateState.Available(release, AppUpdateFailure.ManualInstallRequired),
        )
    }

    @Test
    fun `an installation is not interrupted by checks or switching off`() {
        listOf(Public.Check, Public.Deactivate, Public.Install).forEach { intent ->
            AppUpdateMachineSpec.assertIgnored(downloading, intent)
            AppUpdateMachineSpec.assertIgnored(AppUpdateState.Restarting(release), intent)
        }
    }

    @Test
    fun `switching off forgets the offer`() {
        listOf(
            AppUpdateState.Checking(offered = release),
            AppUpdateState.UpToDate,
            AppUpdateState.CheckFailed(AppUpdateFailure.Network),
            AppUpdateState.Available(release),
        ).forEach { state ->
            AppUpdateMachineSpec.assertTransition(
                from = state,
                intent = Public.Deactivate,
                to = AppUpdateState.Inactive,
            )
        }
        AppUpdateMachineSpec.assertIgnored(AppUpdateState.Inactive, Public.Deactivate)
    }

    @Test
    fun `failed effects become failures of their step`() {
        assertEquals(
            Internal.CheckFailed(AppUpdateFailure.Network),
            AppUpdateMachineSpec.onEffectFailure(AppUpdateEffect.FetchLatest, IllegalStateException()),
        )
        assertEquals(
            Internal.InstallFailed(AppUpdateFailure.InstallFailed),
            AppUpdateMachineSpec.onEffectFailure(AppUpdateEffect.Install(release), IllegalStateException()),
        )
    }
}
