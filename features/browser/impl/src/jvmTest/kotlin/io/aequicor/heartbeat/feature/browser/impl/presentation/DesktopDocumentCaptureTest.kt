package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentBridge
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopDocumentCaptureTest {
    @Test
    fun `a request captures its authority before the native dispatcher queue`() = runTest {
        var generation = 0
        val captures = mutableListOf<Int>()
        val executed = mutableListOf<Int>()
        val bridge = object : BrowserDocumentBridge {
            override fun capture(): BrowserDocumentBridge {
                val captured = generation
                captures += captured
                return BrowserDocumentBridge { _, _ ->
                    if (captured != generation) {
                        BrowserBridgeResult.Failure("Revoked")
                    } else {
                        executed += captured
                        BrowserBridgeResult.Value(JsonPrimitive(captured))
                    }
                }
            }

            override suspend fun call(method: String, arguments: JsonElement): BrowserBridgeResult =
                capture().call(method, arguments)
        }
        val calls = DesktopDocumentCalls(bridge, backgroundScope, StandardTestDispatcher(testScheduler))
        val first = Reply()
        calls.start(1, REQUEST, first)
        assertEquals(listOf(0), captures)
        generation = 1
        runCurrent()
        assertEquals(listOf("failed:Revoked"), first.values)
        assertEquals(emptyList(), executed)

        val next = Reply()
        calls.start(2, REQUEST, next)
        runCurrent()
        assertEquals(listOf(0, 1), captures)
        assertEquals(listOf(1), executed)
        assertEquals(listOf("ok:1"), next.values)
    }

    @Test
    fun `capture failures reply once and do not reserve a pending slot`() = runTest {
        var shouldFail = true
        val bridge = object : BrowserDocumentBridge {
            override fun capture(): BrowserDocumentBridge {
                check(!shouldFail) { "private owner state" }
                return this
            }

            override suspend fun call(method: String, arguments: JsonElement): BrowserBridgeResult =
                BrowserBridgeResult.Value(JsonPrimitive("ok"))
        }
        val calls = DesktopDocumentCalls(bridge, backgroundScope, StandardTestDispatcher(testScheduler))
        val failures = List(BrowserDocumentLimits.CALLS) { index ->
            Reply().also { reply ->
                calls.start(index.toLong(), REQUEST, reply)
                assertEquals(listOf("failed:The call failed"), reply.values)
            }
        }
        shouldFail = false
        val next = Reply()
        calls.start(BrowserDocumentLimits.CALLS.toLong(), REQUEST, next)
        runCurrent()
        failures.forEach { assertEquals(listOf("failed:The call failed"), it.values) }
        assertEquals(listOf("ok:\"ok\""), next.values)
    }

    @Test
    fun `capture cancellation replies before propagating cancellation`() = runTest {
        val bridge = object : BrowserDocumentBridge {
            override fun capture(): BrowserDocumentBridge = throw CancellationException("Owner closed")

            override suspend fun call(method: String, arguments: JsonElement): BrowserBridgeResult = error("unused")
        }
        val calls = DesktopDocumentCalls(bridge, backgroundScope, StandardTestDispatcher(testScheduler))
        val reply = Reply()
        assertFailsWith<CancellationException> { calls.start(1, REQUEST, reply) }
        runCurrent()
        assertEquals(listOf("failed:The document closed"), reply.values)
    }

    private class Reply : DesktopDocumentReply {
        val values = mutableListOf<String>()
        override fun success(json: String) {
            values += "ok:$json"
        }

        override fun failure(message: String) {
            values += "failed:$message"
        }
    }

    private companion object {
        const val REQUEST = """{"method":"save"}"""
    }
}
