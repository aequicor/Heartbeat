package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseGesture
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseInputActivity
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.RecordRequest
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.VisionBudget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import io.aequicor.heartbeat.feature.computeruse.impl.domain.solidGrid
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClipRecordingTest {
    @Test
    fun `a change is recorded as keyframes framed to the changed area until the screen settles`() = runTest {
        val fixture = fixture()
        fixture.capturer.pixels = { index -> if (index == 0) Blank else Changed }
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        val clip = assertIs<ClipOutcome.Recorded>(fixture.coordinator.record(RecordRequest())).clip
        assertEquals(2, clip.frames.size)
        assertTrue(clip.isAutoRoi)
        assertTrue(clip.isStable)
        assertEquals(CaptureRegion(0, 0, 256, 200), clip.roi)
        assertTrue(clip.frames.all { it.capture.roi == clip.roi })
        assertEquals(listOf(0L, clip.durationMillis), clip.frames.map { it.offsetMillis })
        assertTrue(clip.durationMillis in 1_000L..1_500L, "stops about the quiet time after the change")
        assertEquals(400, clip.master.masterWidthPx)
    }

    @Test
    fun `an unchanged screen returns one frame of the whole area after the idle time`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        val clip = assertIs<ClipOutcome.Recorded>(fixture.coordinator.record(RecordRequest())).clip
        assertEquals(1, clip.frames.size)
        assertFalse(clip.isAutoRoi)
        assertFalse(clip.hasChanged)
        assertEquals(CaptureRegion(0, 0, 400, 200), clip.roi)
        assertEquals(1_500L, clip.durationMillis)
    }

    @Test
    fun `a screen that keeps changing stops at the time limit with a bounded frame count`() = runTest {
        val fixture = fixture()
        fixture.capturer.pixels = { index -> block(10 + index * 8 % 300) }
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        val request = RecordRequest(fps = 10, maxDurationMillis = 2_000, maxFrames = 3)
        val clip = assertIs<ClipOutcome.Recorded>(fixture.coordinator.record(request)).clip
        assertEquals(3, clip.frames.size)
        assertFalse(clip.isStable)
        assertEquals(2_000L, clip.durationMillis)
    }

    @Test
    fun `the clip stays inside the active roi and input refers to its last frame`() = runTest {
        val fixture = fixture()
        fixture.capturer.pixels = { index -> if (index == 0) Blank else Changed }
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        val roi = CaptureRegion(80, 40, 120, 80)
        val clip = assertIs<ClipOutcome.Recorded>(fixture.coordinator.record(RecordRequest(), roi)).clip
        assertEquals(roi, clip.roi)
        assertFalse(clip.isAutoRoi)
        assertEquals(
            InputOutcome.Applied,
            fixture.coordinator.input(InputAction.Click(FramePoint(10.0, 10.0)), roi = roi),
        )
        assertEquals(ScreenPoint(10 + 80 / 2 + 5, 20 + 40 / 2 + 5), fixture.injector.points.single())
    }

    @Test
    fun `only the last keyframe can be zoomed because earlier ones are not the master`() = runTest {
        val fixture = fixture()
        fixture.capturer.pixels = { index -> if (index == 0) Blank else Changed }
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        val clip = assertIs<ClipOutcome.Recorded>(fixture.coordinator.record(RecordRequest())).clip
        assertEquals(
            CaptureOutcome.Rejected(ComputerUseFailure.UnknownCapture),
            fixture.coordinator.crop(CropRequest(clip.frames.first().capture.id)),
        )
        assertIs<CaptureOutcome.Produced>(fixture.coordinator.crop(CropRequest(clip.frames.last().capture.id)))
    }

    @Test
    fun `a check that fails after the glide refuses the click before its effect`() = runTest {
        val fixture = fixture()
        fixture.injector.isProgressReported = true
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        fixture.coordinator.capture(CaptureRequest())
        var hasArrived = false
        val activities = mutableListOf<ComputerUseInputActivity>()
        val outcome = fixture.coordinator.input(
            InputAction.Click(FramePoint(10.0, 10.0)),
            guard = { if (hasArrived) ComputerUseFailure.PermissionLost else null },
            onProgress = { _, activity ->
                hasArrived = true
                activities += activity
            },
        )
        assertEquals(InputOutcome.Rejected(ComputerUseFailure.PermissionLost), outcome)
        assertTrue(activities.none { it.effectSequence > 0 })
        assertTrue(fixture.injector.applied.isEmpty())
    }

    @Test
    fun `a target that moved while the pointer glided refuses the click before its effect`() = runTest {
        val fixture = fixture()
        fixture.injector.isProgressReported = true
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        fixture.coordinator.capture(CaptureRequest())
        val outcome = fixture.coordinator.input(
            InputAction.Click(FramePoint(10.0, 10.0)),
            onProgress = { _, _ -> fixture.capturer.bounds = ScreenBounds(40, 20, 200, 100) },
        )
        assertEquals(InputOutcome.Rejected(ComputerUseFailure.TargetResized), outcome)
        assertTrue(fixture.injector.applied.isEmpty())
    }

    @Test
    fun `a slow drift on a frame wider than the working copy is framed in master pixels`() = runTest {
        val fixture = fixture(widthPx = 3840, heightPx = 400)
        val drifting = CaptureRegion(3000, 100, 200, 100)
        // Each frame darkens the block below the per-frame threshold; only the change from the kept frame shows it.
        val blank = solidGrid(3840, 400, WHITE)
        fixture.capturer.pixels = { index -> blank.filled(drifting, gray(255 - minOf(index, 5) * 4)) }
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        val clip = assertIs<ClipOutcome.Recorded>(fixture.coordinator.record(RecordRequest())).clip
        assertTrue(clip.isAutoRoi)
        assertTrue(drifting.isInside(clip.roi), "auto-ROI ${clip.roi} must hold the drifting block")
    }

    @Test
    fun `a finished drag reports the released button to the cursor`() = runTest {
        val fixture = fixture()
        fixture.injector.isProgressReported = true
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        fixture.coordinator.capture(CaptureRequest())
        val activities = mutableListOf<ComputerUseInputActivity>()
        val outcome = fixture.coordinator.input(
            InputAction.Drag(FramePoint(10.0, 10.0), FramePoint(50.0, 20.0)),
            onProgress = { _, activity -> activities += activity },
        )
        assertEquals(InputOutcome.Applied, outcome)
        assertTrue(activities.any { it.gesture is ComputerUseGesture.Drag })
        assertEquals(ComputerUseGesture.Move, activities.last().gesture)
        assertEquals(1L, activities.last().effectSequence)
    }

    @Test
    fun `input glides only inside the captured area narrowed to the active ROI`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        fixture.coordinator.capture(CaptureRequest())
        val roi = CaptureRegion(100, 50, 200, 100)
        fixture.coordinator.input(InputAction.Click(FramePoint(150.0, 80.0)), roi = roi)
        assertEquals(ScreenBounds(60, 45, 100, 50), fixture.injector.lastArea)
        fixture.coordinator.input(InputAction.Click(FramePoint(10.0, 10.0)))
        assertEquals(ScreenBounds(10, 20, 200, 100), fixture.injector.lastArea)
    }

    @Test
    fun `a capture that disappears refuses the recording`() = runTest {
        val fixture = fixture()
        fixture.coordinator.open(Session, ComputerUseMode.Desktop())
        fixture.capturer.bounds = null
        val outcome = fixture.coordinator.record(RecordRequest())
        assertEquals(ClipOutcome.Rejected(ComputerUseFailure.TargetClosed), outcome)
        fixture.capturer.bounds = ScreenBounds(10, 20, 200, 100)
        assertIs<CaptureOutcome.Produced>(fixture.coordinator.capture(CaptureRequest()))
    }

    private class Fixture(
        val capturer: FakeScreenCapturer,
        val injector: FakeInputInjector,
        val coordinator: CaptureCoordinator,
    )

    private fun TestScope.fixture(widthPx: Int = 400, heightPx: Int = 200): Fixture {
        val capturer = FakeScreenCapturer(widthPx, heightPx).apply {
            bounds = ScreenBounds(10, 20, widthPx / 2, heightPx / 2)
            pixels = { Blank }
        }
        val injector = FakeInputInjector()
        val store = FakeFrameStore()
        val encoder = FakeFrameEncoder()
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        val coordinator = CaptureCoordinator(
            capturer,
            FakeWindowCatalog(listOf(windowTarget())),
            injector,
            FramePipeline(encoder, store, VisionBudget(maxTokens = 100_000), dispatchers),
            MasterFrameCache(encoder, store, maxBytes = 64L * 1024 * 1024),
            dispatchers,
            ClipRecorder { testScheduler.currentTime },
        )
        return Fixture(capturer, injector, coordinator)
    }

    private companion object {
        val Session = CaptureSessionId("clip")
        const val WHITE = -1
        const val BLACK = 0xFF000000.toInt()
        val Blank: PixelGrid = solidGrid(400, 200, WHITE)
        val Changed: PixelGrid = block(100)

        fun gray(value: Int): Int = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value

        fun PixelGrid.filled(region: CaptureRegion, color: Int): PixelGrid {
            val pixels = argb.copyOf()
            for (row in region.y until region.bottom) {
                for (column in region.x until region.right) pixels[row * widthPx + column] = color
            }
            return PixelGrid(widthPx, heightPx, pixels)
        }

        fun block(x: Int): PixelGrid {
            val pixels = IntArray(400 * 200) { WHITE }
            for (row in 50 until 70) {
                for (column in x until x + 20) pixels[row * 400 + column] = BLACK
            }
            return PixelGrid(400, 200, pixels)
        }
    }
}
