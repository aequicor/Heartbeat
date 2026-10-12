package io.aequicor.heartbeat.platform.dibundle

import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.browser.api.BrowserEffect
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.CookieManager
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Scripted browser boundary; HTTP/cookies are real, Chromium is covered by its separate native test. */
internal class RecoveryBrowserFixture :
    EffectHandler<BrowserEffect, BrowserIntent>,
    AutoCloseable {
    val pdf: ByteArray = minimalRecoveryPdf()
    val text = "Recovered source evidence"
    val captures = AtomicInteger()
    val denials = AtomicInteger()
    private val client = HttpClient.newBuilder().cookieHandler(CookieManager()).build()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val isUnlocked = exchange.requestHeaders.getFirst("Cookie")?.contains("verified=yes") == true
            val status = if (path == "/unlock" || isUnlocked) 200 else 403
            if (status == 403) denials.incrementAndGet()
            if (path == "/unlock") exchange.responseHeaders.add("Set-Cookie", "verified=yes; Path=/")
            val body = when {
                status == 403 -> "Human action required".encodeToByteArray()
                path == "/document" -> pdf
                else -> text.encodeToByteArray()
            }
            exchange.responseHeaders.add(
                "Content-Type",
                if (path == "/document" && isUnlocked) "application/pdf" else "text/html",
            )
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        start()
    }
    val origin: String = "http://127.0.0.1:${server.address.port}"

    /** The explicit test gesture obtains a cookie; Open and Continue never unlock the fixture themselves. */
    suspend fun unlock() {
        assertEquals(200, get("$origin/unlock").statusCode())
    }

    override suspend fun handle(effect: BrowserEffect, machine: EffectScope<BrowserIntent>) {
        when (effect) {
            BrowserEffect.Observe -> machine.send(BrowserIntent.Internal.AvailabilityChanged(true))
            is BrowserEffect.CancelOperation -> machine.send(BrowserIntent.Internal.OperationCancelled(effect.id))
            is BrowserEffect.Perform -> machine.send(BrowserIntent.Internal.Completed(perform(effect.request)))
            else -> error("Unexpected fixture browser effect: $effect")
        }
    }

    private suspend fun perform(request: BrowserRequest): BrowserResult {
        assertNull(request.owner, "Research recovery must retain explicit human ownership")
        val url = request.arguments.getValue("url").jsonPrimitive.content
        val response = get(url)
        return when (request.operation) {
            BrowserOperation.NewTab -> {
                assertEquals(403, response.statusCode())
                BrowserResult(request.id, buildJsonObject { put("tabId", url) })
            }

            BrowserOperation.CaptureResource -> {
                assertEquals(url, request.tabId, "Continue must address its original source tab")
                captures.incrementAndGet()
                if (response.statusCode() == 403) {
                    BrowserResult(request.id, error = "User action required")
                } else {
                    assertEquals(200, response.statusCode())
                    BrowserResult(
                        request.id,
                        buildJsonObject {
                            put("url", url)
                            if (url.endsWith("/document")) {
                                put("kind", "document")
                                put("mediaType", "application/pdf")
                                put("extension", "pdf")
                                put("contentBase64", Base64.encode(response.body()))
                            } else {
                                put("content", response.body().decodeToString())
                                put("title", "Recovered source")
                            }
                        },
                    )
                }
            }

            else -> error("Unexpected fixture browser operation: ${request.operation}")
        }
    }

    private suspend fun get(url: String): HttpResponse<ByteArray> = withContext(Dispatchers.IO) {
        require(url.startsWith("$origin/"))
        client.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    override fun close() {
        server.stop(0)
    }
}

/** One valid PDF page with a font, content stream, byte offsets, cross-reference table and trailer. */
private fun minimalRecoveryPdf(): ByteArray {
    val stream = "BT /F1 12 Tf 20 50 Td (Recovered PDF evidence) Tj ET\n"
    val objects = listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] " +
            "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Length ${stream.length} >>\nstream\n${stream}endstream",
    )
    val document = StringBuilder("%PDF-1.4\n")
    val offsets = objects.mapIndexed { index, body ->
        document.length.also { document.append("${index + 1} 0 obj\n$body\nendobj\n") }
    }
    val xref = document.length
    document.append("xref\n0 6\n0000000000 65535 f \n")
    offsets.forEach { document.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
    document.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return document.toString().encodeToByteArray()
}
