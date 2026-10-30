package io.aequicor.heartbeat.feature.appupdate.impl.presentation

import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateState
import io.aequicor.heartbeat.feature.appupdate.api.InstallProgress
import io.aequicor.heartbeat.feature.appupdate.api.UnsupportedReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdatesProjectionTest {
    private val release = AppRelease(
        version = "1.5.0",
        assetName = "app.dmg",
        url = "https://example.test/app.dmg",
        sha256 = "",
        size = 10,
        pageUrl = "https://example.test/page",
    )

    @Test
    fun `a host without the machine is disabled`() {
        assertEquals(UpdateStageUi.Disabled, (null as AppUpdateState?).toStage().stage)
    }

    @Test
    fun `unsupported launches name their reason`() {
        assertEquals(
            UpdateStageUi.NotReleaseBuild,
            AppUpdateState.Unsupported(UnsupportedReason.NotReleaseBuild).toStage().stage,
        )
        assertEquals(
            UpdateStageUi.UnsupportedPlatform,
            AppUpdateState.Unsupported(UnsupportedReason.UnsupportedPlatform).toStage().stage,
        )
    }

    @Test
    fun `checking keeps an offered release but is not installable`() {
        val projection = AppUpdateState.Checking(offered = release).toStage()
        assertEquals(UpdateStageUi.Checking, projection.stage)
        assertEquals("1.5.0", projection.version)
        assertFalse(AppUpdatesScreenState(stage = projection.stage).isCheckEnabled)
        assertEquals(UpdateStageUi.Checking, AppUpdateState.Checking().toStage().stage)
    }

    @Test
    fun `an available release is installable unless it needs a manual install`() {
        val ready = AppUpdateState.Available(release).toStage()
        assertEquals(UpdateStageUi.Available, ready.stage)
        assertEquals("https://example.test/page", ready.pageUrl)
        assertTrue(AppUpdatesScreenState(stage = ready.stage).isInstallEnabled)

        val manual = AppUpdateState.Available(release, AppUpdateFailure.ManualInstallRequired).toStage()
        assertEquals(UpdateStageUi.ManualInstall, manual.stage)
        assertFalse(AppUpdatesScreenState(stage = manual.stage).isInstallEnabled)
        assertTrue(AppUpdatesScreenState(stage = manual.stage).isCheckEnabled)
    }

    @Test
    fun `a failed attempt keeps the offer with its failure`() {
        val projection = AppUpdateState.Available(release, AppUpdateFailure.Corrupted).toStage()
        assertEquals(UpdateStageUi.Available, projection.stage)
        assertEquals(UpdateFailureUi.Corrupted, projection.failure)
    }

    @Test
    fun `installation reports its percent and blocks checks`() {
        val downloading = AppUpdateState.Installing(release, InstallProgress.Downloading(25, 100)).toStage()
        assertEquals(UpdateStageUi.Downloading, downloading.stage)
        assertEquals(25, downloading.percent)
        assertFalse(AppUpdatesScreenState(stage = downloading.stage).isCheckEnabled)
        assertEquals(
            UpdateStageUi.Preparing,
            AppUpdateState.Installing(release, InstallProgress.Preparing).toStage().stage,
        )
        assertEquals(UpdateStageUi.Restarting, AppUpdateState.Restarting(release).toStage().stage)
    }

    @Test
    fun `an up to date launch that needs a newer platform is told so`() {
        assertEquals(UpdateStageUi.UpToDate, AppUpdateState.UpToDate().toStage().stage)
        val required = AppUpdateState.UpToDate(isPlatformUpdateRequired = true).toStage()
        assertEquals(UpdateStageUi.PlatformUpdateRequired, required.stage)
        assertTrue(AppUpdatesScreenState(stage = required.stage).isCheckEnabled)
    }

    @Test
    fun `a failed check can be repeated`() {
        val projection = AppUpdateState.CheckFailed(AppUpdateFailure.Network).toStage()
        assertEquals(UpdateStageUi.CheckFailed, projection.stage)
        assertEquals(UpdateFailureUi.Network, projection.failure)
        assertTrue(AppUpdatesScreenState(stage = projection.stage).isCheckEnabled)
    }
}
