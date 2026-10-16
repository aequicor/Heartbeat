package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real CEF proof: shared-host zoom, provisional navigation and trusted wheel input in windowed Chromium. */
internal class DesktopBrowserZoomNativeProbe(
    private val createEngine: (
        LinkedBlockingQueue<BrowserViewState>,
        (BrowserShortcut) -> Unit,
    ) -> DesktopBrowserEngine,
    private val createFrame: () -> JFrame,
    private val awaitTitle: (LinkedBlockingQueue<BrowserViewState>, String) -> Unit,
) {
    fun verify(base: String) {
        val firstStates = LinkedBlockingQueue<BrowserViewState>()
        val secondStates = LinkedBlockingQueue<BrowserViewState>()
        val shortcuts = LinkedBlockingQueue<BrowserShortcut>()
        val engines = mutableListOf<DesktopBrowserEngine>()
        val frames = mutableListOf<JFrame>()
        try {
            SwingUtilities.invokeAndWait {
                for (states in listOf(firstStates, secondStates)) {
                    val engine = createEngine(states) { shortcuts.offer(it) }
                    engines += engine
                    frames += createFrame().apply {
                        add(engine.component, BorderLayout.CENTER)
                        validate()
                    }
                    engine.setZoom(true, 150, "$base/check")
                    engine.start("$base/check")
                }
            }
            awaitTitle(firstStates, "empty-empty")
            awaitTitle(secondStates, "empty-empty")
            val first = engines.first()
            val second = engines.last()
            runBlocking {
                val nativeScale = pixelRatio(first) / 1.5
                awaitRatio(first, nativeScale * 1.5)
                awaitRatio(second, nativeScale * 1.5)
                val otherHost = base.replace("127.0.0.1", "localhost")
                SwingUtilities.invokeAndWait { first.setZoom(true, 200, "$otherHost/check") }
                awaitRatio(first, nativeScale * 1.5)
                awaitRatio(second, nativeScale * 1.5)
                SwingUtilities.invokeAndWait { first.execute(BrowserViewCommand.Load("$otherHost/check")) }
                awaitTitle(firstStates, "empty-empty")
                awaitRatio(first, nativeScale * 2)
                awaitRatio(second, nativeScale * 1.5)
                awaitWheelListener(first)
                wheelIn(first)
                assertEquals(
                    BrowserShortcut.Zoom(BrowserZoomDirection.In),
                    shortcuts.poll(10, TimeUnit.SECONDS),
                    "Trusted Ctrl+wheel must be consumed by the page zoom bridge",
                )
                assertWheelListenerKept(first) {
                    SwingUtilities.invokeAndWait { first.setZoom(true, 250, "$otherHost/check") }
                    awaitRatio(first, nativeScale * 2.5)
                }
                SwingUtilities.invokeAndWait { first.setZoom(false, 250, "$otherHost/check") }
                awaitRatio(first, nativeScale)
            }
        } finally {
            SwingUtilities.invokeAndWait {
                engines.forEach { it.release() }
                frames.forEach { it.dispose() }
            }
            runBlocking { engines.forEach { it.awaitClosed() } }
        }
    }

    /** The listener owns the 80 ms wheel throttle, so a zoom step must keep the same instance installed. */
    private suspend fun assertWheelListenerKept(engine: DesktopBrowserEngine, step: suspend () -> Unit) {
        val remember = """
            (() => {
              const key = Object.keys(window).find(name => name.endsWith('_wheel'));
              window.__zoomProbeWheel = window[key];
              return typeof window.__zoomProbeWheel === 'function';
            })()
        """.trimIndent()
        assertTrue(evaluate(engine, remember).boolean, "The wheel listener must be installed")
        step()
        val isKept = "window[Object.keys(window).find(name => name.endsWith('_wheel'))] === window.__zoomProbeWheel"
        assertTrue(evaluate(engine, isKept).boolean, "A zoom step must keep the wheel listener and its throttle")
    }

    private suspend fun wheelIn(first: DesktopBrowserEngine) {
        first.devTools(
            "Input.dispatchMouseEvent",
            buildJsonObject {
                put("type", "mouseWheel")
                put("x", 100)
                put("y", 100)
                put("deltaX", 0)
                put("deltaY", -120)
                put("modifiers", 2)
            },
        )
    }

    /** Load callbacks install the listener asynchronously; a wheel sent earlier would reach the page unhandled. */
    private suspend fun awaitWheelListener(engine: DesktopBrowserEngine) = withTimeout(10_000) {
        while (!evaluate(engine, "Object.keys(window).some(name => name.endsWith('_wheel'))").boolean) delay(20)
    }

    private suspend fun awaitRatio(engine: DesktopBrowserEngine, expected: Double) = withTimeout(10_000) {
        while (abs(pixelRatio(engine) - expected) > 0.02) delay(20)
    }

    private suspend fun pixelRatio(engine: DesktopBrowserEngine): Double =
        evaluate(engine, "window.devicePixelRatio").double

    private suspend fun evaluate(engine: DesktopBrowserEngine, expression: String): JsonPrimitive {
        val result = engine.devTools(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("returnByValue", true)
            },
        )
        val value = Json.parseToJsonElement(result).jsonObject["result"]?.jsonObject?.get("value")
        return assertNotNull(value, result).jsonPrimitive
    }
}
