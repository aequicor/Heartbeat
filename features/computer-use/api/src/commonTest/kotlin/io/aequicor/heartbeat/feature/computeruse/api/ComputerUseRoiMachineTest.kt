package io.aequicor.heartbeat.feature.computeruse.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineTest.Companion.capturing
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineTest.Companion.click
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineTest.Companion.master
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineTest.Companion.otherOwner
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineTest.Companion.owner
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineTest.Companion.preview
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineTest.Companion.session
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineTest.Companion.windowMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Transitions of the active ROI and of recordings. */
class ComputerUseRoiMachineTest {
    @Test
    fun `an ROI inside the master becomes the working area and travels with capture effects`() {
        val roi = CaptureRegion(100, 100, 400, 300)
        val withMaster = capturing.copy(master = master)
        ComputerUseMachineSpec.assertTransition(
            from = withMaster,
            intent = ComputerUseIntent.Public.SetRoi(roi, session, owner),
            to = withMaster.copy(roi = roi),
        )
        val framed = withMaster.copy(roi = roi)
        ComputerUseMachineSpec.assertTransition(
            from = framed,
            intent = ComputerUseIntent.Public.Capture(CaptureRequest(), "r1"),
            to = framed,
            effects = listOf(ComputerUseEffect.CaptureFrame(CaptureRequest(), "r1", roi)),
        )
        ComputerUseMachineSpec.assertTransition(
            from = framed,
            intent = ComputerUseIntent.Public.SetRoi(null, session, owner),
            to = withMaster,
        )
    }

    @Test
    fun `an ROI is refused outside the master or without one or for another owner`() {
        val withMaster = capturing.copy(master = master)
        ComputerUseMachineSpec.assertIgnored(
            withMaster,
            ComputerUseIntent.Public.SetRoi(CaptureRegion(900, 0, 200, 80), session, owner),
        )
        ComputerUseMachineSpec.assertIgnored(
            capturing,
            ComputerUseIntent.Public.SetRoi(CaptureRegion(0, 0, 10, 10), session, owner),
        )
        ComputerUseMachineSpec.assertIgnored(
            withMaster,
            ComputerUseIntent.Public.SetRoi(CaptureRegion(0, 0, 10, 10), session, otherOwner),
        )
    }

    @Test
    fun `a crop must stay inside the active ROI`() {
        val roi = CaptureRegion(100, 100, 400, 300)
        val framed = capturing.copy(master = master, roi = roi)
        ComputerUseMachineSpec.assertIgnored(
            framed,
            ComputerUseIntent.Public.Crop(CropRequest(master.id, roi = CaptureRegion(0, 0, 50, 50))),
        )
        val inside = CropRequest(master.id, roi = CaptureRegion(150, 150, 50, 50))
        ComputerUseMachineSpec.assertTransition(
            from = framed,
            intent = ComputerUseIntent.Public.Crop(inside),
            to = framed,
            effects = listOf(ComputerUseEffect.ProduceCrop(inside, roi = roi)),
        )
    }

    @Test
    fun `a master that no longer contains the ROI clears it`() {
        val framed = capturing.copy(master = master, roi = CaptureRegion(600, 500, 400, 300))
        val smaller = master.copy(id = CaptureId("m-2"), masterWidthPx = 800, masterHeightPx = 600)
        ComputerUseMachineSpec.assertTransition(
            from = framed,
            intent = ComputerUseIntent.Internal.FrameCaptured(smaller, preview),
            to = framed.copy(master = smaller, lastPreview = preview, frameCount = 1, roi = null),
            outputs = listOf(ComputerUseOutput.FrameReady(preview, master = smaller)),
        )
    }

    @Test
    fun `armed input carries the active ROI to the host`() {
        val roi = CaptureRegion(100, 100, 400, 300)
        val armed = capturing.copy(
            mode = windowMode,
            isInputArmed = true,
            master = master,
            lastPreview = preview,
            roi = roi,
        )
        ComputerUseMachineSpec.assertTransition(
            from = armed,
            intent = ComputerUseIntent.Public.Input(click, "i1"),
            to = armed,
            effects = listOf(ComputerUseEffect.ApplyInput(click, "i1", preview.id, roi)),
        )
    }

    @Test
    fun `a recording runs inside the active roi and its last keyframe becomes the input frame`() {
        val roi = CaptureRegion(100, 100, 400, 300)
        val framed = capturing.copy(master = master, roi = roi)
        val request = RecordRequest()
        ComputerUseMachineSpec.assertTransition(
            from = framed,
            intent = ComputerUseIntent.Public.Record(request, "r1", session, owner),
            to = framed,
            effects = listOf(ComputerUseEffect.RecordClip(request, "r1", roi)),
        )
        ComputerUseMachineSpec.assertIgnored(
            framed,
            ComputerUseIntent.Public.Record(request, "r2", session, otherOwner),
        )
        val last = preview.copy(id = CaptureId("clip-2"))
        val clip = CaptureClip(
            frames = listOf(ClipFrame(preview, 0, 0.0), ClipFrame(last, 1_200, 0.2)),
            roi = roi,
            isAutoRoi = true,
            isStable = true,
            durationMillis = 1_200,
            master = master,
        )
        ComputerUseMachineSpec.assertTransition(
            from = framed,
            intent = ComputerUseIntent.Internal.ClipRecorded(clip, "r1"),
            to = framed.copy(lastPreview = last, frameCount = 2),
            outputs = listOf(ComputerUseOutput.ClipReady(clip, "r1")),
        )
    }

    @Test
    fun `recording requests stay within their limits`() {
        assertFailsWith<IllegalArgumentException> { RecordRequest(fps = 0) }
        assertFailsWith<IllegalArgumentException> { RecordRequest(maxDurationMillis = 20_000) }
        assertFailsWith<IllegalArgumentException> { RecordRequest(maxFrames = 1) }
    }

    @Test
    fun `roi and recording are refused without an open matching session`() {
        val closed = capturing.copy(master = master, isOpen = false)
        val roi = CaptureRegion(0, 0, 10, 10)
        ComputerUseMachineSpec.assertIgnored(closed, ComputerUseIntent.Public.SetRoi(roi, session, owner))
        ComputerUseMachineSpec.assertIgnored(closed, ComputerUseIntent.Public.Record(RecordRequest()))
        val other = CaptureSessionId("other")
        val open = capturing.copy(master = master)
        ComputerUseMachineSpec.assertIgnored(open, ComputerUseIntent.Public.SetRoi(roi, other, owner))
        ComputerUseMachineSpec.assertIgnored(open, ComputerUseIntent.Public.Record(RecordRequest(), "r", other))
        val ready = ComputerUseState.Ready(capturing.capabilities)
        ComputerUseMachineSpec.assertIgnored(ready, ComputerUseIntent.Public.SetRoi(null))
        ComputerUseMachineSpec.assertIgnored(ready, ComputerUseIntent.Public.Record(RecordRequest()))
        ComputerUseMachineSpec.assertIgnored(ComputerUseState.Idle, ComputerUseIntent.Public.Record(RecordRequest()))
    }

    @Test
    fun `clearing the roi needs no master and a new mode starts without one`() {
        val framed = capturing.copy(roi = CaptureRegion(0, 0, 10, 10))
        ComputerUseMachineSpec.assertTransition(
            from = framed,
            intent = ComputerUseIntent.Public.SetRoi(null, session, owner),
            to = capturing,
        )
        val next = CaptureSessionId("s2")
        ComputerUseMachineSpec.assertTransition(
            from = framed,
            intent = ComputerUseIntent.Public.SwitchMode(windowMode, next, owner),
            to = ComputerUseState.Capturing(next, windowMode, owner, capturing.capabilities),
            effects = listOf(ComputerUseEffect.CloseCapture(session), ComputerUseEffect.OpenCapture(windowMode, next)),
            outputs = listOf(ComputerUseOutput.CaptureChanged(windowMode)),
        )
    }

    @Test
    fun `a clip whose master no longer holds the roi clears it`() {
        val framed = capturing.copy(master = master, roi = CaptureRegion(600, 500, 400, 300))
        val smaller = master.copy(id = CaptureId("m-2"), masterWidthPx = 800, masterHeightPx = 600)
        val clip = CaptureClip(
            frames = listOf(ClipFrame(preview, 0, 0.0)),
            roi = CaptureRegion(0, 0, 800, 600),
            isAutoRoi = false,
            isStable = true,
            durationMillis = 1_500,
            master = smaller,
        )
        ComputerUseMachineSpec.assertTransition(
            from = framed,
            intent = ComputerUseIntent.Internal.ClipRecorded(clip),
            to = framed.copy(master = smaller, lastPreview = preview, frameCount = 1, roi = null),
            outputs = listOf(ComputerUseOutput.ClipReady(clip)),
        )
    }

    @Test
    fun `a failed recording effect is rejected for its request`() {
        assertEquals(
            ComputerUseIntent.Internal.Rejected(ComputerUseFailure.CaptureFailed, "r"),
            ComputerUseMachineSpec.onEffectFailure(
                ComputerUseEffect.RecordClip(RecordRequest(), "r"),
                IllegalStateException(),
            ),
        )
    }
}
