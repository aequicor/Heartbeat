package io.aequicor.heartbeat.platform.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopHandoffCompletionTest {
    @Test
    fun backgroundOutcomeWaitsForNativeExitWithoutRequestingActivation() {
        val directory = Files.createTempDirectory("handoff-background")
        assertNull(DesktopHandoff.completion(directory, isNativeAlive = true))
        Files.writeString(directory.resolve("hidden"), "background")
        assertNull(DesktopHandoff.completion(directory, isNativeAlive = true))
        assertEquals(false, DesktopHandoff.completion(directory, isNativeAlive = false))
    }

    @Test
    fun foregroundOutcomeRequestsActivationOnlyAfterNativeExit() {
        val directory = Files.createTempDirectory("handoff-foreground")
        Files.writeString(directory.resolve("hidden"), "activate")
        assertNull(DesktopHandoff.completion(directory, isNativeAlive = true))
        assertEquals(true, DesktopHandoff.completion(directory, isNativeAlive = false))
    }

    @Test
    fun failureOutcomeWaitsForNativeExitAndNeverActivates() {
        val directory = Files.createTempDirectory("handoff-failed")
        Files.writeString(directory.resolve("failed"), "Could not present Studio")
        assertNull(DesktopHandoff.completion(directory, isNativeAlive = true))
        assertEquals(false, DesktopHandoff.completion(directory, isNativeAlive = false))
    }

    @Test
    fun nativeDeathWithoutAnOutcomeRemainsAnExplicitFailure() {
        val directory = Files.createTempDirectory("handoff-crash")
        assertFailsWith<IllegalStateException> {
            DesktopHandoff.completion(directory, isNativeAlive = false)
        }
    }

    @Test
    fun themeFollowsAppliedSnapshotUntilCompletionThenResumesSystemOrForcedPreference() {
        val initial = HandoffPresentation(HandoffBounds(100, 100, 1280, 900), isDark = false, isMaximized = false)
        val session = HandoffSession()
        assertEquals(false, session.darkTheme(initial, isFinished = false, isForcedDark = null))
        session.applied = initial.copy(isDark = true, revision = 2)
        assertEquals(true, session.darkTheme(initial, isFinished = false, isForcedDark = null))
        assertNull(session.darkTheme(initial, isFinished = true, isForcedDark = null))
        assertEquals(false, session.darkTheme(initial, isFinished = true, isForcedDark = false))
    }
}
