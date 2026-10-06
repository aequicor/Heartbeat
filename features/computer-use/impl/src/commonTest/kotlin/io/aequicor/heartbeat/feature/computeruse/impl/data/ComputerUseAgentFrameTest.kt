@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.Capabilities
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.Capturing
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Companion.EmptyArguments
import io.aequicor.heartbeat.feature.computeruse.impl.data.ComputerUseAgentToolsTest.Fixture
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerUseAgentFrameTest {
    @Test
    fun `capture waits for host acknowledgement before requesting a frame`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Ready(Capabilities))
        val args = buildJsonObject { put("mode", "desktop") }
        val spec = fixture.tools.specifications(null).first { it.name == "computer_capture" }
        val approved = fixture.context.copy(authorization = fixture.tools.approval(fixture.context, spec, args))
        val call = async { fixture.tools.execute(approved, spec.name, args) }
        runCurrent()
        assertTrue(fixture.machine.sent.any { it is ComputerUseIntent.Public.BeginCapture })
        assertFalse(fixture.machine.sent.any { it is ComputerUseIntent.Public.Capture })
        val opened = fixture.machine.state.value as ComputerUseState.Capturing
        fixture.machine.state.value = opened.copy(isOpen = true)
        advanceUntilIdle()
        val result = call.await()
        assertFalse(result.isError)
        assertEquals("AQID", result.images.single().data)
        assertEquals(1, fixture.machine.sent.count { it is ComputerUseIntent.Public.Capture })
    }

    @Test
    fun `screenshot and zoom return the image bytes in the same response`() = runTest {
        val calls = listOf(
            "computer_screenshot" to EmptyArguments,
            "computer_zoom" to buildJsonObject { put("tile", "0:0") },
        )
        for ((name, arguments) in calls) {
            val fixture = Fixture(this, Capturing)
            val result = fixture.tools.execute(fixture.approved(name, arguments), name, arguments)
            assertFalse(result.isError, "$name: ${result.text}")
            val image = result.images.single()
            assertEquals("image/png", image.mimeType)
            assertEquals("AQID", image.data)
            assertTrue(result.text.contains("widthPx"))
            assertFalse(result.text.contains("Open the file"))
        }
    }

    @Test
    fun `a missing frame fails instead of returning an invisible success`() = runTest {
        val fixture = Fixture(this, Capturing)
        fixture.frames.files.clear()
        val result = fixture.tools.execute(fixture.context, "computer_screenshot", EmptyArguments)
        assertTrue(result.isError)
        assertEquals("CaptureFailed", result.text)
        assertTrue(result.images.isEmpty())
    }
}
