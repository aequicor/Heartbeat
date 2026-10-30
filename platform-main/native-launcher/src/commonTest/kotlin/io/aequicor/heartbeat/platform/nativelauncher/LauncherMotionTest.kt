package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionProgress
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LauncherMotionTest {
    @Test
    fun quietLogoFrameMatchesEmbeddedComposeJoinContract() {
        val clock = LauncherMotion(isFirstLaunch = true, isMotionReduced = false, start = 0)
        val frame = clock.frame(4_200, LauncherSceneStatus())
        assertEquals(0.0, frame.welcome)
        assertEquals(0.0, frame.name)
        assertEquals(1.0, frame.shell)
        assertEquals(1.0, frame.pulse)
        assertEquals(1.0, frame.pulseDraw)
        assertEquals(0.0, frame.spread)
        assertEquals(0.0, frame.download)
        assertEquals(0.0, frame.opening)
    }

    @Test
    fun cachedStartupImmediatelyUsesStudioWithoutDownloadActions() {
        val clock = LauncherMotion(isFirstLaunch = false, isMotionReduced = false, start = 0)
        val status = LauncherSceneStatus(isOpening = true)
        clock.tick(0, status, false)
        val frame = clock.frame(0, status)
        assertEquals(1.0, frame.opening)
        assertEquals(0.0, frame.welcome)
        assertEquals(0.0, frame.download)
        assertFalse(status.isDownloadCancellable)
    }

    @Test
    fun earlyReadinessDoesNotWaitForIntro() {
        val clock = LauncherMotion(isFirstLaunch = true, isMotionReduced = false, start = 0)
        val status = LauncherSceneStatus(isOpening = true)
        clock.tick(300, status, false)
        assertEquals(1.0, clock.frame(700, status).opening)
        assertEquals(0.0, clock.frame(700, status).welcome)
    }

    @Test
    fun reducedMotionSkipsIntroShimmerAndTipRotation() {
        val clock = LauncherMotion(isFirstLaunch = true, isMotionReduced = true, start = 0)
        val status = downloading()
        for (now in 0L..20_000L step 100L) clock.tick(now, status, false)
        val frame = clock.frame(20_000, status)
        assertEquals(0.0, frame.welcome)
        assertEquals(1.0, frame.download)
        assertEquals(-1.0, frame.shimmer)
        assertEquals(0, clock.tipIndex)
        clock.changeTip(1, 20_000)
        assertEquals(1, clock.tipIndex)
    }

    @Test
    fun tipsPauseAndChangeOnlyAfterOutgoingTextFades() {
        val clock = LauncherMotion(isFirstLaunch = false, isMotionReduced = false, start = 0)
        val status = downloading()
        for (now in 0L..10_000L step 100L) clock.tick(now, status, true)
        assertEquals(0, clock.tipIndex)
        clock.changeTip(1, 10_000)
        clock.tick(10_100, status, true)
        assertEquals(0, clock.tipIndex)
        assertTrue(clock.frame(10_100, status).tipAlpha < 1)
        clock.tick(10_220, status, true)
        assertEquals(1, clock.tipIndex)
        assertEquals(0.0, clock.frame(10_220, status).tipAlpha)
        assertEquals(1.0, clock.frame(10_640, status).tipAlpha)
    }

    @Test
    fun unknownTotalNeverPretendsToHaveAPercentage() {
        val status = LauncherSceneStatus(ProvisionProgress(ProvisionStage.DOWNLOAD, downloadedBytes = 12_450_000))
        assertNull(status.fraction)
        assertEquals("12,4", downloadMegabytes(status.progress.downloadedBytes))
        assertTrue(status.isDownloadCancellable)
        assertFalse(status.copy(progress = ProvisionProgress(ProvisionStage.EXTRACT)).isDownloadCancellable)
        assertFalse(status.copy(error = "Network error").isDownloadCancellable)
    }

    private fun downloading() = LauncherSceneStatus(ProvisionProgress(ProvisionStage.DOWNLOAD))
}
