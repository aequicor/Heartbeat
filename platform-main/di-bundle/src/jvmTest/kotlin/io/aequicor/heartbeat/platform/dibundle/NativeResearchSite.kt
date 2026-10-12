package io.aequicor.heartbeat.platform.dibundle

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** Loopback challenge: only the explicit browser button sets the cookie required by both sources. */
internal class NativeResearchSite : AutoCloseable {
    val pdf = minimalRecoveryPdf()
    val text = "Native browser research evidence"
    val deniedRequests = AtomicInteger()
    val unlockedRequests = AtomicInteger()
    val documentRequests = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/", ::respond)
        start()
    }
    val origin = "http://127.0.0.1:${server.address.port}"

    private fun respond(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val isUnlocked = exchange.requestHeaders.getFirst("Cookie")?.contains("verified=yes") == true
        val isUnlock = path == "/unlock"
        val status = if (isUnlocked || isUnlock) 200 else 403
        if (isUnlock) {
            unlockedRequests.incrementAndGet()
            exchange.responseHeaders.add("Set-Cookie", "verified=yes; Path=/; HttpOnly; SameSite=Lax")
        }
        val document = path == "/document" && isUnlocked
        val body = when {
            status == 403 -> {
                deniedRequests.incrementAndGet()
                (
                    "<html><head><title>Unlock source</title></head><body>" +
                        "<button onclick=\"fetch('/unlock').then(() => location.reload())\">Unlock</button>" +
                        "</body></html>"
                ).encodeToByteArray()
            }

            document -> {
                documentRequests.incrementAndGet()
                pdf
            }

            else -> "<html><head><title>Recovered source</title></head><body>$text</body></html>".encodeToByteArray()
        }
        exchange.responseHeaders.add("Content-Type", if (document) "application/pdf" else "text/html; charset=utf-8")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        exchange.close()
    }

    override fun close() = server.stop(0)
}
