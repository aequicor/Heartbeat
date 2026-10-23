package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.util.UUID

/** One bounded, credential-free request on loopback. Blocking channel IO is interruptible by the coroutine. */
internal class CodexInventoryEndpoint : AutoCloseable {
    private val server = ServerSocketChannel.open()
    private val path = "/${UUID.randomUUID()}/v1/responses"

    init {
        try {
            server.bind(InetSocketAddress("127.0.0.1", 0))
        } catch (error: IOException) {
            server.close()
            throw error
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${(server.localAddress as InetSocketAddress).port}${path.removeSuffix(
        "/responses",
    )}"

    suspend fun receive(dispatcher: CoroutineDispatcher): JsonObject = try {
        runInterruptible(dispatcher) { capture() }
    } catch (error: IOException) {
        // NIO signals interruption with ClosedByInterruptException rather than InterruptedException.
        currentCoroutineContext().ensureActive()
        throw error
    }

    fun capture(): JsonObject = server.accept().use { channel ->
        val input = Channels.newInputStream(channel)
        val header = readHeader(input)
        val lines = header.split("\r\n")
        if (lines.first() != "POST $path HTTP/1.1") inventoryFailure()
        val entries = lines.asSequence().drop(1).filter(String::isNotEmpty).map {
            val parts = it.split(':', limit = 2)
            if (parts.size != 2) inventoryFailure()
            parts[0].lowercase() to parts[1].trim()
        }.toList()
        if (entries.map { it.first }.distinct().size != entries.size) inventoryFailure()
        val headers = entries.toMap()
        if (listOf(
                "authorization",
                "transfer-encoding",
                "content-encoding",
            ).any(headers::containsKey)
        ) {
            inventoryFailure()
        }
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..MAX_BODY } ?: inventoryFailure()
        val bytes = input.readNBytes(length)
        if (bytes.size != length) inventoryFailure()
        val request = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: inventoryFailure()
        val body = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{" +
            "\"id\":\"inventory\",\"object\":\"response\",\"status\":\"completed\",\"output\":[]," +
            "\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1}}}\n\n"
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
        Channels.newOutputStream(channel).write(response.toByteArray())
        request
    }

    private fun readHeader(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < MAX_HEADER) {
            val next = input.read()
            if (next < 0) inventoryFailure()
            bytes.add(next.toByte())
            if (bytes.size >= HEADER_END.size && bytes.takeLast(HEADER_END.size) == HEADER_END) {
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
        }
        inventoryFailure()
    }

    override fun close() = server.close()

    private companion object {
        const val MAX_HEADER = 16 * 1024
        const val MAX_BODY = 1024 * 1024
        val HEADER_END = "\r\n\r\n".toByteArray().toList()
    }
}
