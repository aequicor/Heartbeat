package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.RecordRequest
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.Capturing
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.EmptyArguments
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Fixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** `<family>_record` and `observeMs` of the input tools. */
class ComputerUseRecordToolTest {
    @Test
    fun `recording tools and observeMs exist only while the recording toggle is on`() = runTest {
        val off = Fixture(this, Capturing).tools.specifications(null)
        assertTrue(off.none { it.name.endsWith("_record") })
        assertFalse(
            "observeMs" in off.first { it.name == "computer_click" }.inputSchema.getValue("properties").jsonObject,
        )
        val on = Fixture(this, Capturing, isRecording = true).tools.specifications(null)
        assertTrue(on.any { it.name == "computer_record" } && on.any { it.name == "window_record" })
        assertTrue("observeMs" in on.first { it.name == "window_type" }.inputSchema.getValue("properties").jsonObject)
        assertTrue(Fixture(this, Capturing).tools.catalog.any { it.name == "computer_record" })
    }

    @Test
    fun `record returns every keyframe image with its timing`() = runTest {
        val fixture = Fixture(this, Capturing, isRecording = true)
        val arguments = buildJsonObject {
            put("fps", 8)
            put("durationMs", 2_000)
            put("maxFrames", 3)
        }
        val reply = fixture.tools.execute(fixture.context, "computer_record", arguments)
        assertFalse(reply.isError, reply.text)
        assertEquals(2, reply.images.size)
        assertTrue("\"tMs\":900" in reply.text)
        val sent = fixture.machine.sent.filterIsInstance<ComputerUseIntent.Public.Record>().single()
        assertEquals(RecordRequest(fps = 8, maxDurationMillis = 2_000, maxFrames = 3), sent.request)
    }

    @Test
    fun `recording is refused while the toggle is off or the request is out of range`() = runTest {
        val off = Fixture(this, Capturing)
        assertEquals("RecordingDisabled", off.tools.execute(off.context, "computer_record", EmptyArguments).text)
        val observed = buildJsonObject { put("observeMs", 500) }
        val screenshot = off.tools.execute(off.context, "computer_screenshot", observed)
        assertNotEquals("RecordingDisabled", screenshot.text, "observeMs belongs to input tools only")
        val on = Fixture(this, Capturing, isRecording = true)
        val reply = on.tools.execute(on.context, "computer_record", buildJsonObject { put("fps", 60) })
        assertEquals("InvalidRecording", reply.text)
        assertTrue(on.machine.sent.isEmpty())
    }

    @Test
    fun `observeMs records right after the applied input`() = runTest {
        val fixture = Fixture(this, Capturing, isRecording = true)
        val arguments = buildJsonObject {
            put("x", 5)
            put("y", 5)
            put("observeMs", 1_500)
        }
        val approved = fixture.approved("computer_click", arguments)
        assertTrue(approved.authorization!!.title.endsWith("and record up to 1500 ms afterwards"))
        val reply = fixture.tools.execute(approved, "computer_click", arguments)
        assertFalse(reply.isError, reply.text)
        assertTrue("\"applied\":\"Applied computer_click\"" in reply.text)
        assertEquals(2, reply.images.size)
        val order = fixture.machine.sent.map { it::class.simpleName }
        assertTrue(order.indexOf("Input") < order.indexOf("Record"))
        val record = fixture.machine.sent.filterIsInstance<ComputerUseIntent.Public.Record>().single()
        assertEquals(1_500L, record.request.maxDurationMillis)
    }

    @Test
    fun `observeMs without the recording toggle is refused before the input`() = runTest {
        val fixture = Fixture(this, Capturing)
        val arguments = buildJsonObject {
            put("x", 5)
            put("y", 5)
            put("observeMs", 500)
        }
        val reply = fixture.tools.execute(fixture.approved("computer_click", arguments), "computer_click", arguments)
        assertEquals("RecordingDisabled", reply.text)
        assertTrue(fixture.machine.sent.isEmpty())
    }
}
