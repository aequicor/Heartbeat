package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.BridgePaths
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.FrameLink
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.SocketFrameLink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Why [UnixSocketBridgeServer.serve] ended without being cancelled. */
internal sealed interface ServeResult {
    /** Another live process (a second Heartbeat build sharing the data directory) already serves the socket. */
    data object Occupied : ServeResult

    /** The socket could not be bound. */
    data class Failed(val cause: IOException) : ServeResult
}

/**
 * The app end of the bridge: a Unix domain socket in a directory only the current user can open. Hosts started by
 * the browser connect to it; each accepted connection is served by its own coroutine. A live socket of another
 * process is never taken over; a stale file left by a crash is replaced. The socket file is removed when serving
 * ends, so hosts report Heartbeat as unavailable.
 */
internal class UnixSocketBridgeServer(
    private val socket: Path,
    private val io: CoroutineDispatcher,
    private val maxConnections: Int = MAX_CONNECTIONS,
) {
    private val log = Log.tag("BridgeServer")

    /**
     * Listens until the calling coroutine is cancelled. [onListening] runs once the socket accepts connections and
     * [onConnections] after every change of the live connection count.
     */
    suspend fun serve(
        onListening: () -> Unit,
        onConnections: (Int) -> Unit = {},
        handler: suspend (FrameLink) -> Unit,
    ): ServeResult {
        val server = try {
            runInterruptible(io) { bind() }
        } catch (e: IOException) {
            log.w(e) { "Bridge socket could not be bound" }
            return ServeResult.Failed(e)
        } ?: return ServeResult.Occupied
        log.i { "Bridge socket listening" }
        val live = AtomicInteger()
        try {
            coroutineScope {
                onListening()
                acceptForever(this, server, live, onConnections, handler)
            }
        } catch (e: IOException) {
            // Cancellation interrupts the blocking accept, which closes the channel: that is not a failure.
            currentCoroutineContext().ensureActive()
            log.w(e) { "Bridge socket stopped accepting" }
            return ServeResult.Failed(e)
        } finally {
            server.close()
            Files.deleteIfExists(socket)
            log.i { "Bridge socket closed" }
        }
    }

    private suspend fun acceptForever(
        scope: CoroutineScope,
        server: ServerSocketChannel,
        live: AtomicInteger,
        onConnections: (Int) -> Unit,
        handler: suspend (FrameLink) -> Unit,
    ): Nothing {
        while (true) {
            val client = runInterruptible(io) { server.accept() }
            if (live.get() >= maxConnections) {
                log.w { "Bridge connection refused: limit=$maxConnections" }
                client.close()
                continue
            }
            onConnections(live.incrementAndGet())
            scope.launch { serveClient(client, live, onConnections, handler) }
        }
    }

    private suspend fun serveClient(
        client: SocketChannel,
        live: AtomicInteger,
        onConnections: (Int) -> Unit,
        handler: suspend (FrameLink) -> Unit,
    ) {
        log.i { "Bridge connection opened" }
        try {
            SocketFrameLink(client).use { handler(it) }
        } finally {
            onConnections(live.decrementAndGet())
            log.i { "Bridge connection closed" }
        }
    }

    /** Binds the socket, or returns `null` when another process is listening on it. */
    private fun bind(): ServerSocketChannel? {
        BridgePaths.ensurePrivateDirectory(checkNotNull(socket.parent))
        if (Files.exists(socket)) {
            if (isAlive()) {
                log.i { "Bridge socket is served by another process" }
                return null
            }
            log.i { "Replacing a stale bridge socket" }
            Files.deleteIfExists(socket)
        }
        return ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
    }

    private fun isAlive(): Boolean = try {
        SocketChannel.open(UnixDomainSocketAddress.of(socket)).close()
        true
    } catch (e: IOException) {
        log.w(e.redactedFailure()) { "Bridge socket refused a probe, treating it as stale" }
        false
    }

    private companion object {
        const val MAX_CONNECTIONS = 4
    }
}
