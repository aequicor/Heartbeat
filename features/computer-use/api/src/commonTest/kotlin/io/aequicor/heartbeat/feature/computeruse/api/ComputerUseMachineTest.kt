package io.aequicor.heartbeat.feature.computeruse.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.test.Test
import kotlin.test.assertFalse

class ComputerUseMachineTest {

    @Test
    fun `start from Idle probes availability`() {
        ComputerUseMachineSpec.assertTransition(
            from = ComputerUseState.Idle,
            intent = ComputerUseIntent.Public.Start,
            to = ComputerUseState.Checking,
            effects = listOf(ComputerUseEffect.ProbeAvailability),
        )
    }

    @Test
    fun `a second start while checking is ignored`() {
        ComputerUseMachineSpec.assertIgnored(ComputerUseState.Checking, ComputerUseIntent.Public.Start)
    }

    @Test
    fun `available capabilities open Ready and enumerate windows`() {
        ComputerUseMachineSpec.assertTransition(
            from = ComputerUseState.Checking,
            intent = ComputerUseIntent.Internal.Available(capabilities),
            to = ComputerUseState.Ready(capabilities),
            effects = listOf(ComputerUseEffect.EnumerateWindows),
        )
    }

    @Test
    fun `a blocked probe reports the missing permission`() {
        val blockers = listOf(ComputerUseBlocker.ScreenRecordingPermission)
        ComputerUseMachineSpec.assertTransition(
            from = ComputerUseState.Checking,
            intent = ComputerUseIntent.Internal.Blocked(blockers),
            to = ComputerUseState.Unavailable(blockers),
            outputs = listOf(ComputerUseOutput.PermissionRequired(blockers)),
        )
    }

    @Test
    fun `retry from Unavailable probes again`() {
        ComputerUseMachineSpec.assertTransition(
            from = ComputerUseState.Unavailable(listOf(ComputerUseBlocker.AccessibilityPermission)),
            intent = ComputerUseIntent.Public.Retry,
            to = ComputerUseState.Checking,
            effects = listOf(ComputerUseEffect.ProbeAvailability),
        )
    }

    @Test
    fun `begin desktop capture opens the capture device and announces the mode`() {
        ComputerUseMachineSpec.assertTransition(
            from = ready,
            intent = ComputerUseIntent.Public.BeginCapture(desktopMode, owner, session),
            to = ComputerUseState.Capturing(session, desktopMode, owner, capabilities),
            effects = listOf(ComputerUseEffect.OpenCapture(desktopMode, session)),
            outputs = listOf(ComputerUseOutput.CaptureChanged(desktopMode)),
        )
    }

    @Test
    fun `window capture is refused when the host cannot capture windows`() {
        val limited = ready.copy(capabilities = capabilities.copy(isWindowCaptureAvailable = false))
        ComputerUseMachineSpec.assertIgnored(
            limited,
            ComputerUseIntent.Public.BeginCapture(windowMode, owner, session),
        )
    }

    @Test
    fun `capturing a frame without a session is ignored`() {
        ComputerUseMachineSpec.assertIgnored(ready, ComputerUseIntent.Public.Capture(CaptureRequest()))
    }

    @Test
    fun `a captured frame stores the master and notifies with the preview`() {
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Internal.FrameCaptured(master, preview, tiles),
            to = capturing.copy(master = master, lastPreview = preview, frameCount = 1),
            outputs = listOf(ComputerUseOutput.FrameReady(preview, tiles, master = master)),
        )
    }

    @Test
    fun `a crop inside the master is produced`() {
        val withMaster = capturing.copy(master = master)
        val crop = CropRequest(master.id, region = CaptureRegion(0, 0, 100, 80))
        ComputerUseMachineSpec.assertTransition(
            from = withMaster,
            intent = ComputerUseIntent.Public.Crop(crop),
            to = withMaster,
            effects = listOf(ComputerUseEffect.ProduceCrop(crop)),
        )
    }

    @Test
    fun `a crop outside the master is ignored`() {
        val withMaster = capturing.copy(master = master)
        ComputerUseMachineSpec.assertIgnored(
            withMaster,
            ComputerUseIntent.Public.Crop(CropRequest(master.id, region = CaptureRegion(900, 0, 200, 80))),
        )
    }

    @Test
    fun `a crop without a stored master is ignored`() {
        ComputerUseMachineSpec.assertIgnored(
            capturing,
            ComputerUseIntent.Public.Crop(CropRequest(CaptureId("unknown"), region = CaptureRegion(0, 0, 10, 10))),
        )
    }

    @Test
    fun `input without arming is ignored`() {
        ComputerUseMachineSpec.assertIgnored(capturing, ComputerUseIntent.Public.Input(click))
    }

    @Test
    fun `desktop input is refused while the desktop allowance is off`() {
        val armed = capturing.copy(isInputArmed = true)
        ComputerUseMachineSpec.assertIgnored(armed, ComputerUseIntent.Public.Input(click))
    }

    @Test
    fun `desktop input applies once the host allows it`() {
        val allowed = capturing.copy(
            isInputArmed = true,
            capabilities = capabilities.copy(isDesktopInputAllowed = true),
        )
        ComputerUseMachineSpec.assertTransition(
            from = allowed,
            intent = ComputerUseIntent.Public.Input(click),
            to = allowed,
            effects = listOf(ComputerUseEffect.ApplyInput(click)),
        )
    }

    @Test
    fun `window input applies when armed without the desktop allowance`() {
        val armed = capturing.copy(mode = windowMode, isInputArmed = true)
        ComputerUseMachineSpec.assertTransition(
            from = armed,
            intent = ComputerUseIntent.Public.Input(click),
            to = armed,
            effects = listOf(ComputerUseEffect.ApplyInput(click)),
        )
    }

    @Test
    fun `arming input is refused when the host has no input capability`() {
        val withoutInput = capturing.copy(capabilities = capabilities.copy(isInputAvailable = false))
        ComputerUseMachineSpec.assertIgnored(withoutInput, ComputerUseIntent.Public.ArmInput(true))
    }

    @Test
    fun `authorized input arms only its current capture frame`() {
        val observed = capturing.copy(lastPreview = preview)
        ComputerUseMachineSpec.assertTransition(
            from = observed,
            intent = ComputerUseIntent.Public.ArmInput(true, observed.session, preview.id),
            to = observed.copy(isInputArmed = true),
        )
        ComputerUseMachineSpec.assertIgnored(
            observed,
            ComputerUseIntent.Public.ArmInput(true, CaptureSessionId("another-session"), preview.id),
        )
        ComputerUseMachineSpec.assertIgnored(
            observed,
            ComputerUseIntent.Public.ArmInput(true, observed.session, CaptureId("another-frame")),
        )
        ComputerUseMachineSpec.assertIgnored(
            observed.copy(isInputArmed = true),
            ComputerUseIntent.Public.Input(click, expectedCapture = CaptureId("another-frame")),
        )
    }

    @Test
    fun `a frame named without its session still has to match`() {
        val observed = capturing.copy(lastPreview = preview)
        ComputerUseMachineSpec.assertIgnored(
            observed,
            ComputerUseIntent.Public.ArmInput(true, expectedCapture = CaptureId("another-frame")),
        )
        ComputerUseMachineSpec.assertTransition(
            from = observed,
            intent = ComputerUseIntent.Public.ArmInput(true, expectedCapture = preview.id),
            to = observed.copy(isInputArmed = true),
        )
    }

    @Test
    fun `authorization for a capture cannot arm a ready machine`() {
        ComputerUseMachineSpec.assertIgnored(
            ComputerUseState.Ready(capabilities),
            ComputerUseIntent.Public.ArmInput(true, capturing.session, preview.id),
        )
        ComputerUseMachineSpec.assertIgnored(
            ComputerUseState.Ready(capabilities),
            ComputerUseIntent.Public.ArmInput(true, expectedCapture = preview.id),
        )
    }

    @Test
    fun `switching mode re-enters capturing with the new session`() {
        val started = capturing.copy(master = master, lastPreview = preview, frameCount = 3)
        val next = CaptureSessionId("s2")
        ComputerUseMachineSpec.assertTransition(
            from = started,
            intent = ComputerUseIntent.Public.SwitchMode(windowMode, next),
            to = ComputerUseState.Capturing(next, windowMode, owner, capabilities),
            effects = listOf(
                ComputerUseEffect.CloseCapture(started.session),
                ComputerUseEffect.OpenCapture(windowMode, next),
            ),
            outputs = listOf(ComputerUseOutput.CaptureChanged(windowMode)),
        )
    }

    @Test
    fun `ending a capture disarms input and purges the master frames`() {
        val started = capturing.copy(isInputArmed = true, master = master, frameCount = 2)
        ComputerUseMachineSpec.assertTransition(
            from = started,
            intent = ComputerUseIntent.Public.EndCapture,
            to = ComputerUseState.Ready(capabilities),
            effects = listOf(ComputerUseEffect.CloseCapture(session), ComputerUseEffect.PurgeMasters(session)),
            outputs = listOf(ComputerUseOutput.CaptureChanged(null)),
        )
    }

    @Test
    fun `releasing another owner keeps the session`() {
        ComputerUseMachineSpec.assertIgnored(
            capturing,
            ComputerUseIntent.Public.OwnerReleased(CaptureOwner.Panel),
        )
    }

    @Test
    fun `releasing the owning agent ends the session`() {
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Public.OwnerReleased(owner),
            to = ComputerUseState.Ready(capabilities),
            effects = listOf(ComputerUseEffect.CloseCapture(session), ComputerUseEffect.PurgeMasters(session)),
            outputs = listOf(ComputerUseOutput.CaptureChanged(null)),
        )
    }

    @Test
    fun `a lost target fails the session and releases the device`() {
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Internal.CaptureLost(ComputerUseFailure.TargetClosed),
            to = ComputerUseState.Failed(ComputerUseFailure.TargetClosed),
            effects = listOf(ComputerUseEffect.CloseCapture(session), ComputerUseEffect.PurgeMasters(session)),
            outputs = listOf(ComputerUseOutput.CaptureChanged(null)),
        )
    }

    @Test
    fun `a rejected request keeps the session and reports the reason`() {
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Internal.Rejected(ComputerUseFailure.EncodingTooLarge),
            to = capturing,
            outputs = listOf(ComputerUseOutput.Rejected(ComputerUseFailure.EncodingTooLarge)),
        )
    }

    @Test
    fun `a failed frame keeps the session and reports the reason`() {
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Internal.Failed(ComputerUseFailure.CaptureFailed),
            to = capturing,
            outputs = listOf(ComputerUseOutput.Rejected(ComputerUseFailure.CaptureFailed)),
        )
    }

    @Test
    fun `revoke stops everything from the capturing state`() {
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Public.Revoke,
            to = ComputerUseState.Idle,
            effects = listOf(ComputerUseEffect.CloseCapture(session), ComputerUseEffect.PurgeMasters(session)),
            outputs = listOf(ComputerUseOutput.Revoked),
        )
    }

    @Test
    fun `revoke from Idle only reports itself`() {
        ComputerUseMachineSpec.assertTransition(
            from = ComputerUseState.Idle,
            intent = ComputerUseIntent.Public.Revoke,
            to = ComputerUseState.Idle,
            effects = listOf(ComputerUseEffect.CloseCapture(), ComputerUseEffect.PurgeMasters()),
            outputs = listOf(ComputerUseOutput.Revoked),
        )
    }

    @Test
    fun `a window list refreshes both idle and capturing states`() {
        ComputerUseMachineSpec.assertTransition(
            from = ready,
            intent = ComputerUseIntent.Public.RefreshTargets,
            to = ready,
            effects = listOf(ComputerUseEffect.EnumerateWindows),
        )
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Internal.TargetsLoaded(listOf(target)),
            to = capturing.copy(targets = listOf(target)),
        )
    }

    @Test
    fun `state printing hides window titles and frame paths`() {
        assertFalse(target.toString().contains("Meeting notes"))
        assertFalse(target.toString().contains("Notes"))
        assertFalse(master.toString().contains("captures"))
        assertFalse(preview.toString().contains("png"))
    }

    @Test
    fun `capture cannot run before the host acknowledges opening`() {
        ComputerUseMachineSpec.assertIgnored(
            capturing.copy(isOpen = false),
            ComputerUseIntent.Public.Capture(CaptureRequest()),
        )
        ComputerUseMachineSpec.assertTransition(
            from = capturing.copy(isOpen = false),
            intent = ComputerUseIntent.Internal.CaptureOpened(session),
            to = capturing,
        )
        ComputerUseMachineSpec.assertIgnored(
            capturing,
            ComputerUseIntent.Internal.CaptureOpened(CaptureSessionId("other")),
        )
    }

    @Test
    fun `capture replies retain their request identifier`() {
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Public.Capture(CaptureRequest(), "a"),
            to = capturing,
            effects = listOf(ComputerUseEffect.CaptureFrame(CaptureRequest(), "a")),
        )
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Internal.FrameCaptured(master, preview, tiles, "a"),
            to = capturing.copy(master = master, lastPreview = preview, frameCount = 1),
            outputs = listOf(ComputerUseOutput.FrameReady(preview, tiles, "a", master)),
        )
    }

    @Test
    fun `a changed approved capture cannot receive input`() {
        val armed = capturing.copy(isInputArmed = true, mode = windowMode, lastPreview = preview)
        ComputerUseMachineSpec.assertIgnored(
            armed,
            ComputerUseIntent.Public.Input(
                click,
                expectedSession = CaptureSessionId("old"),
                expectedCapture = preview.id,
            ),
        )
        ComputerUseMachineSpec.assertIgnored(
            armed,
            ComputerUseIntent.Public.Input(click, expectedSession = session, expectedCapture = CaptureId("old")),
        )
    }

    @Test
    fun `late cancellation and capture cannot affect a replacement session`() {
        val old = CaptureSessionId("old")
        ComputerUseMachineSpec.assertIgnored(capturing, ComputerUseIntent.Public.CancelSession(old))
        ComputerUseMachineSpec.assertIgnored(
            capturing,
            ComputerUseIntent.Public.Capture(CaptureRequest(), expectedSession = old),
        )
        ComputerUseMachineSpec.assertIgnored(
            capturing.copy(master = master),
            ComputerUseIntent.Public.Crop(CropRequest(master.id), expectedSession = old),
        )
    }

    @Test
    fun `panel takeover replaces the owner and ignores old agent release`() {
        val replacement = CaptureSessionId("replacement")
        val switched = capturing.copy(
            session = replacement,
            mode = windowMode,
            owner = CaptureOwner.Panel,
            isOpen = false,
        )
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Public.SwitchMode(windowMode, replacement, CaptureOwner.Panel, session),
            to = switched,
            effects = listOf(
                ComputerUseEffect.CloseCapture(capturing.session),
                ComputerUseEffect.OpenCapture(windowMode, replacement),
            ),
            outputs = listOf(ComputerUseOutput.CaptureChanged(windowMode)),
        )
        ComputerUseMachineSpec.assertIgnored(switched, ComputerUseIntent.Public.OwnerReleased(owner))
    }

    @Test
    fun `cleanup acknowledgement refers only to the cleaned session`() {
        ComputerUseMachineSpec.assertTransition(
            from = capturing,
            intent = ComputerUseIntent.Internal.SessionClosed(CaptureSessionId("old")),
            to = capturing,
            outputs = listOf(ComputerUseOutput.SessionClosed(CaptureSessionId("old"))),
        )
    }

    private companion object {
        val capabilities = ComputerUseCapabilities(
            isCaptureAvailable = true,
            isWindowCaptureAvailable = true,
            isInputAvailable = true,
            isDesktopInputAllowed = false,
            monitors = listOf(MonitorInfo(MonitorId("m1"), ScreenBounds(0, 0, 1920, 1080), isPrimary = true)),
        )
        val target = WindowTarget(
            id = WindowId("w1"),
            application = "Notes",
            title = "Meeting notes",
            bounds = ScreenBounds(10, 20, 800, 600),
        )
        val desktopMode = ComputerUseMode.Desktop()
        val windowMode = ComputerUseMode.Window(target)
        val owner = CaptureOwner.Agent(
            SessionRef(EngineId("pi"), SessionSourceId("source"), "native"),
            TurnId("turn"),
        )
        val session = CaptureSessionId("s1")
        val ready = ComputerUseState.Ready(capabilities)
        val capturing = ComputerUseState.Capturing(session, desktopMode, owner, capabilities, isOpen = true)
        val master = CaptureRef(
            id = CaptureId("m-1"),
            session = session,
            format = CaptureFormat.Png,
            widthPx = 1000,
            heightPx = 800,
            region = CaptureRegion(0, 0, 1000, 800),
            masterWidthPx = 1000,
            masterHeightPx = 800,
            bytes = 40_000,
            estimatedTokens = 0,
            path = "/tmp/captures/s1/m-1.png",
            sequence = 1,
            isMaster = true,
        )
        val preview = master.copy(id = CaptureId("p-1"), widthPx = 500, heightPx = 400, isMaster = false)
        val tiles = TileGrid(1, 1, 1024, 1024, 64)
        val click = InputAction.Click(FramePoint(10.0, 10.0))
    }
}
