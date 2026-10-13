package io.aequicor.heartbeat.feature.appupdate.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateEffect
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateIntent.Internal
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.api.UnsupportedReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppUpdateEffectsTest {
    private val installation = AppInstallation(
        AppVersion(1, 3, 0),
        GitHubRepository("aequicor", "Heartbeat"),
        ReleasePlatform.MacOsArm64,
    )
    private val release = AppRelease(
        version = "1.4.0",
        assetName = "Heartbeat-1.4.0-macos-arm64.dmg",
        url = "https://github.com/aequicor/Heartbeat/releases/download/v1.4.0/Heartbeat-1.4.0-macos-arm64.dmg",
        sha256 = "a".repeat(64),
        size = 1_000,
        pageUrl = "https://github.com/aequicor/Heartbeat/releases/tag/v1.4.0",
    )

    @Test
    fun `a launch that cannot update itself never asks GitHub`() = runTest {
        val machine = RecordingScope()
        val releases = FakeReleases(release)

        effects(
            InstallationCheck.Unsupported(UnsupportedReason.NotReleaseBuild),
            releases,
        ).handle(AppUpdateEffect.FetchLatest, machine)

        assertEquals(listOf<AppUpdateIntent>(Internal.Unsupported(UnsupportedReason.NotReleaseBuild)), machine.sent)
        assertEquals(0, releases.requests)
    }

    @Test
    fun `the newest release is reported with a failed earlier attempt`() = runTest {
        val machine = RecordingScope()
        val installer = FakeInstaller(previous = PreviousAttempt.Failed)

        effects(releases = FakeReleases(release), installer = installer).handle(AppUpdateEffect.FetchLatest, machine)

        assertEquals(
            listOf<AppUpdateIntent>(Internal.Checked(release, AppUpdateFailure.PreviousAttemptFailed)),
            machine.sent,
        )
    }

    @Test
    fun `a successful earlier attempt and no newer release are up to date`() = runTest {
        val machine = RecordingScope()

        effects(installer = FakeInstaller(previous = PreviousAttempt.Succeeded))
            .handle(AppUpdateEffect.FetchLatest, machine)

        assertEquals(listOf<AppUpdateIntent>(Internal.Checked(null)), machine.sent)
    }

    @Test
    fun `a failed check reports its failure`() = runTest {
        val machine = RecordingScope()
        val releases = AppReleaseSource { _, _, _ -> updateFailure(AppUpdateFailure.RateLimited) }

        effects(releases = releases).handle(AppUpdateEffect.FetchLatest, machine)

        assertEquals(listOf<AppUpdateIntent>(Internal.CheckFailed(AppUpdateFailure.RateLimited)), machine.sent)
    }

    @Test
    fun `an installation reports each new percent the preparation and the started installer`() = runTest {
        val machine = RecordingScope()
        val installer = FakeInstaller(chunks = listOf(1L, 5L, 10L, 15L, 500L, 1_000L))

        effects(installer = installer).handle(AppUpdateEffect.Install(release), machine)

        assertEquals(
            listOf<AppUpdateIntent>(
                Internal.Progress(1, 1_000),
                Internal.Progress(10, 1_000),
                Internal.Progress(500, 1_000),
                Internal.Progress(1_000, 1_000),
                Internal.Preparing,
                Internal.Launched,
            ),
            machine.sent,
        )
        assertEquals(listOf(installation to release), installer.installed)
    }

    @Test
    fun `a failed installation reports its failure`() = runTest {
        val machine = RecordingScope()
        val installer = FakeInstaller(failure = AppUpdateFailure.ManualInstallRequired)

        effects(installer = installer).handle(AppUpdateEffect.Install(release), machine)

        assertTrue(machine.sent.last() == Internal.InstallFailed(AppUpdateFailure.ManualInstallRequired))
    }

    private fun effects(
        check: InstallationCheck = InstallationCheck.Supported(installation),
        releases: AppReleaseSource = FakeReleases(null),
        installer: FakeInstaller = FakeInstaller(),
    ) = AppUpdateEffects({ check }, releases, installer)

    private class RecordingScope : EffectScope<AppUpdateIntent> {
        val sent = mutableListOf<AppUpdateIntent>()

        override suspend fun send(intent: AppUpdateIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    private class FakeReleases(private val release: AppRelease?) : AppReleaseSource {
        var requests = 0

        override suspend fun newer(
            repository: GitHubRepository,
            installed: AppVersion,
            platform: ReleasePlatform,
        ): AppRelease? {
            requests++
            return release
        }
    }

    private class FakeInstaller(
        private val previous: PreviousAttempt = PreviousAttempt.None,
        private val chunks: List<Long> = emptyList(),
        private val failure: AppUpdateFailure? = null,
    ) : UpdateInstaller {
        val installed = mutableListOf<Pair<AppInstallation, AppRelease>>()

        override suspend fun previousAttempt(installed: AppVersion): PreviousAttempt = previous

        override suspend fun install(
            installation: AppInstallation,
            release: AppRelease,
            progress: InstallProgressSink,
        ) {
            failure?.let { updateFailure(it) }
            chunks.forEach { progress.downloading(it, release.size) }
            progress.preparing()
            installed += installation to release
        }
    }
}
