package io.aequicor.heartbeat.feature.appupdate.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateOfferTest {
    private val release = AppRelease(
        version = "1.4.0",
        assetName = "Heartbeat-1.4.0-windows-x64-setup.exe",
        url = "https://github.com/aequicor/Heartbeat/releases/download/v1.4.0/Heartbeat-1.4.0-windows-x64-setup.exe",
        sha256 = "b".repeat(64),
        size = 200,
        pageUrl = "https://github.com/aequicor/Heartbeat/releases/tag/v1.4.0",
    )

    @Test
    fun `nothing is offered without a newer release`() {
        listOf(
            AppUpdateState.Inactive,
            AppUpdateState.Checking(),
            AppUpdateState.UpToDate,
            AppUpdateState.CheckFailed(AppUpdateFailure.Network),
            AppUpdateState.Unsupported(UnsupportedReason.NotReleaseBuild),
        ).forEach { assertNull(it.appUpdateOffer(), it.toString()) }
    }

    @Test
    fun `rechecking a manual update keeps the release page action`() {
        val manual = AppUpdateState.Checking(release, AppUpdateFailure.ManualInstallRequired).appUpdateOffer()

        assertEquals(AppUpdateFailure.ManualInstallRequired, manual?.failure)
        assertEquals(release.pageUrl, manual?.pageUrl)
        assertFalse(manual?.isInstallable == true)
    }

    @Test
    fun `an available or rechecked release is ready to install`() {
        val ready = AppUpdateOffer(release.version, release.pageUrl, AppUpdatePhase.Ready)
        assertEquals(ready, AppUpdateState.Available(release).appUpdateOffer())
        assertEquals(ready, AppUpdateState.Checking(offered = release).appUpdateOffer())
        assertTrue(ready.isInstallable)
    }

    @Test
    fun `a failure is shown and a manual install is not retried by the button`() {
        val retry = AppUpdateState.Available(release, AppUpdateFailure.Corrupted).appUpdateOffer()
        assertEquals(AppUpdateFailure.Corrupted, retry?.failure)
        assertTrue(retry?.isInstallable == true)
        val manual = AppUpdateState.Available(release, AppUpdateFailure.ManualInstallRequired).appUpdateOffer()
        assertFalse(manual?.isInstallable == true)
    }

    @Test
    fun `installation phases carry the download percentage`() {
        assertEquals(
            AppUpdatePhase.Downloading(50),
            AppUpdateState.Installing(release, InstallProgress.Downloading(100, 200)).appUpdateOffer()?.phase,
        )
        assertEquals(
            AppUpdatePhase.Downloading(null),
            AppUpdateState.Installing(release, InstallProgress.Downloading(100, null)).appUpdateOffer()?.phase,
        )
        assertEquals(
            AppUpdatePhase.Downloading(100),
            AppUpdateState.Installing(release, InstallProgress.Downloading(300, 200)).appUpdateOffer()?.phase,
        )
        assertEquals(
            AppUpdatePhase.Preparing,
            AppUpdateState.Installing(release, InstallProgress.Preparing).appUpdateOffer()?.phase,
        )
        val restarting = AppUpdateState.Restarting(release).appUpdateOffer()
        assertEquals(AppUpdatePhase.Restarting, restarting?.phase)
        assertFalse(restarting?.isInstallable == true)
    }
}
