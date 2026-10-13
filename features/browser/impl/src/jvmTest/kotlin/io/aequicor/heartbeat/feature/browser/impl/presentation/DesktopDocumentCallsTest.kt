package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentBridge
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDocumentCallsTest {
    @Test
    fun `a call is a method name with JSON arguments within the limits`() {
        assertEquals(
            DesktopDocumentCall("prs", buildJsonObject { put("state", "open") }),
            parseDesktopDocumentCall("""{"method":"prs","args":{"state":"open"},"extra":1}"""),
        )
        assertEquals(DesktopDocumentCall("prs", JsonNull), parseDesktopDocumentCall("""{"method":"prs"}"""))
        listOf(
            """{"method":" "}""",
            """{"method":1}""",
            """{"method":"${"m".repeat(BrowserDocumentLimits.METHOD_CHARS + 1)}"}""",
            """["prs"]""",
            """{"method":""",
            """{"method":"prs","args":"${"x".repeat(BrowserDocumentLimits.CALL_BYTES)}"}""",
            """{"method":"prs","args":${"[".repeat(
                BrowserDocumentLimits.JSON_DEPTH,
            )}${"]".repeat(BrowserDocumentLimits.JSON_DEPTH)}}""",
        ).forEach { assertNull(parseDesktopDocumentCall(it), it.take(40)) }
        val brackets = "\"${"[".repeat(BrowserDocumentLimits.JSON_DEPTH * 2)}\""
        assertEquals(
            JsonPrimitive(brackets.trim('"')),
            parseDesktopDocumentCall("""{"method":"m","args":$brackets}""")?.arguments,
        )
    }

    @Test
    fun `results and failures reach the page once, bounded`() = runTest(UnconfinedTestDispatcher()) {
        val calls = DesktopDocumentCalls(
            { method, arguments ->
                when (method) {
                    "echo" -> BrowserBridgeResult.Value(arguments)
                    "large" -> BrowserBridgeResult.Value(JsonPrimitive("x".repeat(BrowserDocumentLimits.RESULT_BYTES)))
                    "fail" -> BrowserBridgeResult.Failure("e".repeat(BrowserDocumentLimits.ERROR_CHARS + 10))
                    else -> error("broken $arguments")
                }
            },
            backgroundScope,
        )
        assertEquals(listOf("ok:{\"a\":1}"), calls.reply(1, """{"method":"echo","args":{"a":1}}"""))
        assertEquals(listOf("failed:The result is too large"), calls.reply(2, """{"method":"large"}"""))
        assertEquals(
            listOf("failed:${"e".repeat(BrowserDocumentLimits.ERROR_CHARS)}"),
            calls.reply(3, """{"method":"fail"}"""),
        )
        assertEquals(listOf("failed:The call failed"), calls.reply(4, """{"method":"throw","args":"secret"}"""))
        assertEquals(listOf("failed:The call is malformed or too large"), calls.reply(5, "not json"))
    }

    @Test
    fun `pending calls are bounded, cancelled calls get no reply, a closed document fails the rest`() =
        runTest(UnconfinedTestDispatcher()) {
            val started = mutableListOf<String>()
            val release = CompletableDeferred<Unit>()
            val document = TestScope(UnconfinedTestDispatcher(testScheduler))
            val calls = DesktopDocumentCalls(
                BrowserDocumentBridge { method, _ ->
                    started += method
                    if (method == "wait") awaitCancellation()
                    release.await()
                    BrowserBridgeResult.Value(JsonPrimitive(method))
                },
                document,
            )
            val replies = List(BrowserDocumentLimits.CALLS) { mutableListOf<String>() }
            replies.forEachIndexed { index, reply ->
                calls.start(
                    index.toLong(),
                    """{"method":"${if (index == 0) "wait" else "m$index"}"}""",
                    reply.recorder(),
                )
            }
            assertEquals(listOf("failed:Too many calls are pending"), calls.reply(99, """{"method":"more"}"""))

            calls.cancel(0)
            calls.cancel(0)
            assertEquals(emptyList(), replies[0])
            release.complete(Unit)
            assertEquals(listOf("ok:\"m1\""), replies[1])

            val late = mutableListOf<String>()
            calls.start(10, """{"method":"wait"}""", late.recorder())
            document.cancel()
            assertEquals(listOf("failed:The document closed"), late)
            assertTrue(replies.drop(1).all { it.size == 1 })
        }

    private fun DesktopDocumentCalls.reply(id: Long, request: String): List<String> =
        mutableListOf<String>().also { start(id, request, it.recorder()) }

    private fun MutableList<String>.recorder() = object : DesktopDocumentReply {
        override fun success(json: String) {
            add("ok:$json")
        }

        override fun failure(message: String) {
            add("failed:$message")
        }
    }
}
