package io.aequicor.heartbeat.feature.browser.impl.presentation

import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Real Chromium comparison of ordinary links, inherited referrer policies and later typed navigation. */
internal class DesktopBrowserReferrerProbe(
    private val createEngine: (LinkedBlockingQueue<BrowserViewState>, ((BrowserCommand.Load) -> Unit)?) ->
    DesktopBrowserEngine,
    private val createFrame: () -> JFrame,
    private val awaitTitle: (LinkedBlockingQueue<BrowserViewState>, String) -> Unit,
) {
    fun verify(server: HttpServer) {
        val base = "http://127.0.0.1:${server.address.port}"
        val headers = LinkedBlockingQueue<String>()
        linkedReferrerEndpoints(server, headers)
        for ((fork, policy) in listOf(false, true).flatMap { fork ->
            listOf("default", "noreferrer", "origin", "fragment", "redirect").map { fork to it }
        }) {
            val source = "$base/referrer-source?$policy"
            val expected = mapOf(
                "noreferrer" to "<missing>",
                "origin" to "$base/",
                "redirect" to "$base/",
            )[policy] ?: source
            val opened = LinkedBlockingQueue<BrowserCommand.Load>()
            val states = LinkedBlockingQueue<BrowserViewState>()
            val runtimes = mutableListOf<DesktopBrowserEngine>()
            val frames = mutableListOf<JFrame>()
            fun create(
                load: BrowserCommand.Load,
                callback: ((BrowserCommand.Load) -> Unit)? = null,
            ): DesktopBrowserEngine {
                var created: DesktopBrowserEngine? = null
                SwingUtilities.invokeAndWait {
                    val runtime = createEngine(states, callback)
                    created = runtime
                    runtimes += runtime
                    frames += createFrame().apply {
                        add(runtime.component, BorderLayout.CENTER)
                        validate()
                    }
                    runtime.execute(BrowserViewCommand.Load(load.url, load.referrer))
                    runtime.start("")
                }
                return assertNotNull(created)
            }
            val parent = create(BrowserCommand.Load(source), if (fork) ({ opened.offer(it) }) else null)
            try {
                awaitTitle(states, "Referrer source")
                runBlocking {
                    val driver = DesktopBrowserAutomation(parent::devTools, parent::captureResource)
                    val read = driver.perform(BrowserRequest("read", BrowserOperation.Read, "tab"))
                    driver.perform(
                        BrowserRequest(
                            "click",
                            BrowserOperation.Click,
                            "tab",
                            arguments = buildJsonObject {
                                put("snapshotId", read.data.getValue("snapshotId"))
                                put("element", 0)
                            },
                        ),
                    )
                }
                val destination = if (fork) {
                    create(assertNotNull(opened.poll(10, TimeUnit.SECONDS), "No linked navigation: $policy"))
                } else {
                    parent
                }
                assertEquals(
                    expected,
                    headers.poll(10, java.util.concurrent.TimeUnit.SECONDS),
                    "fork=$fork policy=$policy",
                )
                awaitTitle(states, "Referrer target")
                SwingUtilities.invokeAndWait {
                    destination.execute(BrowserViewCommand.Load("$base/referrer-target"))
                }
                assertEquals("<missing>", headers.poll(10, TimeUnit.SECONDS), "Typed navigation reused $policy")
            } finally {
                SwingUtilities.invokeAndWait {
                    runtimes.forEach { it.release() }
                    frames.forEach { it.dispose() }
                }
                runBlocking { runtimes.forEach { it.awaitClosed() } }
            }
        }
    }

    private fun linkedReferrerEndpoints(server: HttpServer, headers: LinkedBlockingQueue<String>) {
        server.createContext("/referrer-source") { exchange ->
            val attributes = when (exchange.requestURI.query) {
                "noreferrer" -> "rel='noreferrer'"
                "origin" -> "referrerpolicy='origin'"
                else -> ""
            }
            val target = when (exchange.requestURI.query) {
                "fragment" -> "/referrer-target#section"
                "redirect" -> "/referrer-redirect"
                else -> "/referrer-target"
            }
            val bytes = (
                "<title>Referrer source</title>" +
                    "<a href='$target' $attributes>Next</a>"
            ).toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/referrer-redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/referrer-target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/referrer-target") { exchange ->
            headers.offer(exchange.requestHeaders.getFirst("Referer") ?: "<missing>")
            val bytes = "<title>Referrer target</title>".toByteArray()
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
