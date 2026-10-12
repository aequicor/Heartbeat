package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.host

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.NativeFraming
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFrame
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.HostState
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.encodeFrame
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A live connection from the host to the app socket. */
internal interface AppLink : Closeable {
    /** Frames from the app. */
    val input: InputStream

    /** Frames to the app. */
    val output: OutputStream
}

/** Chrome's end of standard output is closed; ends the host instead of the current app link. */
internal class BrowserGoneException(cause: IOException) : IOException("Browser output closed", cause)

/** Opens [AppLink]s; `null` while Heartbeat is not listening. */
internal fun interface AppConnector {
    /** @throws IOException when a socket exists but refuses the connection (stale after a crash). */
    fun connect(): AppLink?
}

/**
 * The whole native-messaging host: Chrome's standard streams on one side, the app socket on the other. Frames pass
 * through unparsed. The host only writes its own [BridgeFrame.Host] frames: the app state towards the extension
 * and the calling origin as the first frame towards the app. While Heartbeat is not running the host stays alive
 * and polls the socket, so Chrome keeps one host process per browser and the extension reconnects on its own.
 */
internal class NativeHostRelay(
    private val browserInput: InputStream,
    private val browserOutput: OutputStream,
    private val origin: String,
    private val connector: AppConnector,
    private val retryDelayMillis: (attempt: Int) -> Long = ::defaultRetryDelay,
) {
    private val stopped = CountDownLatch(1)
    private val lock = Any()
    private var link: AppLink? = null

    private val isStopped: Boolean get() = stopped.count == 0L

    /** Relays until Chrome closes standard input; returns the process exit code. */
    fun run(): Int {
        thread(isDaemon = true, name = "chromium-host-browser") { pumpBrowser() }
        return try {
            writeBrowser(encodeFrame(BridgeFrame.Host(state = HostState.Waiting)))
            relayApp()
            log.i { "Chromium host finished" }
            EXIT_OK
        } catch (e: IOException) {
            // Chrome's end of standard output is gone: nobody is left to relay to.
            log.w(e) { "Chromium host lost the browser" }
            EXIT_BROWSER_GONE
        } finally {
            stop()
        }
    }

    private fun relayApp() {
        var attempt = 0
        var failures = 0
        while (!isStopped) {
            val connected = try {
                connector.connect()
            } catch (e: IOException) {
                // A stale socket after a crash refuses every poll: warn once per waiting period.
                if (failures++ == 0) {
                    log.w(e) { "Heartbeat socket refused the connection" }
                } else {
                    log.v { "Still refused" }
                }
                null
            }
            if (connected == null) {
                if (stopped.await(retryDelayMillis(attempt++), TimeUnit.MILLISECONDS)) return
                continue
            }
            attempt = 0
            failures = 0
            serve(connected)
            if (!isStopped) writeBrowser(encodeFrame(BridgeFrame.Host(state = HostState.Waiting)))
        }
    }

    private fun serve(connected: AppLink) {
        try {
            val hello = BridgeFrame.Host(origin = origin, hostVersion = HOST_VERSION)
            NativeFraming.write(connected.output, encodeFrame(hello))
            synchronized(lock) {
                if (isStopped) return
                link = connected
            }
            log.i { "Chromium host connected to Heartbeat" }
            writeBrowser(encodeFrame(BridgeFrame.Host(state = HostState.Connected)))
            pumpApp(connected)
        } catch (e: BrowserGoneException) {
            throw e
        } catch (e: IOException) {
            if (isStopped) log.d { "Heartbeat link closed on stop" } else log.w(e) { "Heartbeat link failed" }
        } finally {
            synchronized(lock) { link = null }
            connected.close()
        }
        log.i { "Chromium host disconnected from Heartbeat" }
    }

    private fun pumpApp(connected: AppLink) {
        while (true) {
            val frame = NativeFraming.read(connected.input, BridgeProtocol.MAX_FROM_BROWSER_BYTES) ?: return
            if (frame.size > BridgeProtocol.MAX_TO_BROWSER_BYTES) {
                log.w { "Dropped an app frame above the browser cap bytes=${frame.size}" }
            } else {
                writeBrowser(frame)
            }
        }
    }

    private fun pumpBrowser() {
        try {
            while (true) {
                val frame = NativeFraming.read(browserInput, BridgeProtocol.MAX_FROM_BROWSER_BYTES) ?: break
                forward(frame)
            }
            log.i { "Browser closed the native-messaging port" }
        } catch (e: IOException) {
            log.w(e) { "Browser stream failed" }
        } finally {
            stop()
        }
    }

    @HighFrequency
    private fun forward(frame: ByteArray) {
        val current = synchronized(lock) { link }
        if (current == null) {
            log.v { "Dropped a browser frame while Heartbeat is unavailable bytes=${frame.size}" }
            return
        }
        try {
            synchronized(current) { NativeFraming.write(current.output, frame) }
        } catch (e: IOException) {
            // The app side reports the broken link on its own reader; the extension times the call out.
            log.w(e) { "Could not forward a browser frame" }
        }
    }

    @HighFrequency
    private fun writeBrowser(frame: ByteArray) {
        try {
            synchronized(browserOutput) { NativeFraming.write(browserOutput, frame) }
        } catch (e: IOException) {
            throw BrowserGoneException(e)
        }
    }

    private fun stop() {
        stopped.countDown()
        val current = synchronized(lock) { link.also { link = null } }
        try {
            current?.close()
        } catch (e: IOException) {
            log.w(e) { "Could not close the Heartbeat link" }
        }
    }

    internal companion object {
        /** Host behaviour version, reported to the app in the first frame. */
        const val HOST_VERSION = 1
        const val EXIT_OK = 0
        const val EXIT_BROWSER_GONE = 1
        private const val FIRST_RETRY_MILLIS = 1_000L
        private const val MAX_RETRY_MILLIS = 5_000L
        private val log = Log.tag("NativeHostRelay")

        fun defaultRetryDelay(attempt: Int): Long = (FIRST_RETRY_MILLIS * (attempt + 1)).coerceAtMost(MAX_RETRY_MILLIS)
    }
}
