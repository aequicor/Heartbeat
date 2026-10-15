package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.MonitorId
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.Capabilities
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.Capturing
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.EmptyArguments
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.Frame
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.Owner
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.Session
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.roi
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Fixture
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `computer_*` and `window_*` families and the ROI tool. */
class ComputerUseToolFamiliesTest {
    @Test
    fun `desktop and window families declare the same verbs and the shared status`() = runTest {
        val fixture = Fixture(this, Capturing)
        val names = fixture.tools.specifications(null).map { it.name }.toSet()
        assertTrue("computer_status" in names)
        assertTrue("window_list" in names)
        assertFalse("computer_windows" in names)
        assertFalse("window_status" in names)
        val verbs = listOf("capture", "screenshot", "zoom", "roi", "click", "drag", "scroll", "type", "key", "release")
        for (verb in verbs) {
            assertTrue("computer_$verb" in names, verb)
            assertTrue("window_$verb" in names, verb)
        }
    }

    @Test
    fun `a family tool refuses the capture of the other family`() = runTest {
        val fixture = Fixture(this, Capturing)
        val text = buildJsonObject { put("text", "hello") }
        assertEquals(
            "CaptureModeMismatch",
            fixture.tools.execute(fixture.context, "window_screenshot", EmptyArguments).text,
        )
        assertEquals(
            "CaptureModeMismatch",
            fixture.tools.execute(fixture.approved("window_type", text), "window_type", text).text,
        )
        assertTrue(fixture.machine.sent.isEmpty())
        assertFalse(fixture.tools.execute(fixture.context, "computer_screenshot", EmptyArguments).isError)
    }

    @Test
    fun `desktop capture resolves a named monitor and refuses an unknown one`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Ready(Capabilities))
        val unknown = buildJsonObject { put("monitorId", "missing") }
        assertEquals(
            "UnknownMonitor",
            fixture.tools.execute(fixture.approved("computer_capture", unknown), "computer_capture", unknown).text,
        )
        val primary = buildJsonObject { put("monitorId", "primary") }
        val call = async {
            fixture.tools.execute(fixture.approved("computer_capture", primary), "computer_capture", primary)
        }
        runCurrent()
        val begin = fixture.machine.sent.filterIsInstance<ComputerUseIntent.Public.BeginCapture>().single()
        assertEquals(ComputerUseMode.Desktop(MonitorId("primary")), begin.mode)
        call.cancel()
    }

    @Test
    fun `window capture needs a known window`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Ready(Capabilities))
        val arguments = buildJsonObject { put("windowId", "missing") }
        val reply = fixture.tools.execute(fixture.approved("window_capture", arguments), "window_capture", arguments)
        assertEquals("UnknownWindow", reply.text)
        assertTrue(fixture.machine.sent.isEmpty())
    }

    @Test
    fun `the roi tool maps preview pixels into the master and serves a frame of it`() = runTest {
        val preview = Frame.copy(id = CaptureId("half"), widthPx = 10, heightPx = 5)
        val master = Frame.copy(id = CaptureId("master"), isMaster = true)
        val fixture = Fixture(this, Capturing.copy(master = master, lastPreview = preview))
        val reply = fixture.tools.execute(
            fixture.context,
            "computer_roi",
            buildJsonObject { put("roi", roi(1, 1, 4, 2)) },
        )
        assertFalse(reply.isError, reply.text)
        val roi = CaptureRegion(2, 2, 8, 4)
        assertEquals(ComputerUseIntent.Public.SetRoi(roi, Session, Owner), fixture.machine.sent.first())
        assertEquals(roi, (fixture.machine.state.value as ComputerUseState.Capturing).roi)
        assertTrue(fixture.machine.sent.last() is ComputerUseIntent.Public.Capture)
    }

    @Test
    fun `the roi tool refuses rectangles outside the master and clears on request`() = runTest {
        val fixture = Fixture(this, Capturing.copy(roi = CaptureRegion(0, 0, 10, 5)))
        val outside = fixture.tools.execute(
            fixture.context,
            "computer_roi",
            buildJsonObject { put("roi", roi(15, 0, 10, 10, space = "master")) },
        )
        assertEquals("RegionOutOfBounds", outside.text)
        val cleared = fixture.tools.execute(fixture.context, "computer_roi", buildJsonObject { put("clear", true) })
        assertFalse(cleared.isError, cleared.text)
        assertNull((fixture.machine.state.value as ComputerUseState.Capturing).roi)
    }

    @Test
    fun `a one-off frame outside the active roi is refused before sending`() = runTest {
        val fixture = Fixture(this, Capturing.copy(roi = CaptureRegion(0, 0, 10, 5)))
        val reply = fixture.tools.execute(
            fixture.context,
            "computer_screenshot",
            buildJsonObject { put("roi", roi(12, 6, 4, 2, space = "master")) },
        )
        assertEquals("OutsideRoi", reply.text)
        assertTrue(fixture.machine.sent.isEmpty())
    }

    @Test
    fun `old tool names map to the window family so saved policies keep restricting it`() = runTest {
        val legacy = Fixture(this, Capturing).tools.legacyNames
        assertEquals(setOf("window_click"), legacy["computer_click"])
        assertEquals(setOf("window_type"), legacy["computer_type"])
        assertEquals(setOf("window_list"), legacy["computer_windows"])
    }
}
