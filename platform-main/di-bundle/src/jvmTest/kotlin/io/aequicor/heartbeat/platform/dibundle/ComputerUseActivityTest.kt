package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.MonitorId
import io.aequicor.heartbeat.feature.computeruse.api.MonitorInfo
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.platform.dibundle.root.ComputerUseActivity
import io.aequicor.heartbeat.platform.dibundle.root.ComputerUseScreenBounds
import io.aequicor.heartbeat.platform.dibundle.root.computerUseActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerUseActivityTest {
    private val left = MonitorInfo(MonitorId("left"), ScreenBounds(-1920, 0, 1920, 1080), false)
    private val primary = MonitorInfo(MonitorId("primary"), ScreenBounds(0, 0, 1440, 900), true)
    private val capabilities = ComputerUseCapabilities(true, true, true, true, listOf(left, primary))
    private val owner = CaptureOwner.Agent(SessionRef(EngineId("pi"), SessionSourceId("local"), "chat"), TurnId("turn"))
    private val capture = ComputerUseState.Capturing(
        CaptureSessionId("capture"),
        ComputerUseMode.Desktop(),
        owner,
        capabilities,
        isOpen = true,
    )

    @Test
    fun `whole desktop capture marks each monitor without merging their perimeters`() {
        assertEquals(
            ComputerUseActivity(
                true,
                listOf(ComputerUseScreenBounds(-1920, 0, 1920, 1080), ComputerUseScreenBounds(0, 0, 1440, 900)),
            ),
            capture.computerUseActivity(),
        )
    }

    @Test
    fun `selected monitor only shades that monitor`() {
        assertEquals(
            listOf(ComputerUseScreenBounds(-1920, 0, 1920, 1080)),
            capture.copy(mode = ComputerUseMode.Desktop(monitor = left.id)).computerUseActivity().screens,
        )
    }

    @Test
    fun `window capture pins the session without shading the desktop`() {
        val target = WindowTarget(WindowId("window"), "app", "title", ScreenBounds(0, 0, 800, 600))
        val activity = capture.copy(mode = ComputerUseMode.Window(target)).computerUseActivity()
        assertTrue(activity.isActive)
        assertTrue(activity.screens.isEmpty())
    }

    @Test
    fun `manual captures releases and failures never leave presentation active`() {
        val inactive = listOf(
            capture.copy(owner = CaptureOwner.Panel),
            ComputerUseState.Idle,
            ComputerUseState.Checking,
            ComputerUseState.Ready(capabilities),
            ComputerUseState.Unavailable(emptyList()),
            ComputerUseState.Failed(ComputerUseFailure.PermissionLost),
        )
        inactive.forEach { state ->
            assertFalse(state.computerUseActivity().isActive)
            assertTrue(state.computerUseActivity().screens.isEmpty())
        }
    }

    @Test
    fun `opening or switching a capture keeps session pinned but waits for full capture to shade`() {
        val preparing = capture.copy(isOpen = false).computerUseActivity()
        assertTrue(preparing.isActive)
        assertTrue(preparing.screens.isEmpty())
    }
}
