package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.FrameLink
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.NativeFraming
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * [FrameTransport] over a host connection. A broken or out-of-protocol stream ends the connection like a clean close;
 * a failed send surfaces as [BridgeErrorCode.UNAVAILABLE] to the caller.
 */
internal class LinkTransport(private val link: FrameLink, private val io: CoroutineDispatcher) : FrameTransport {
    private val log = Log.tag("LinkTransport")
    private val writeLock = Mutex()

    @HighFrequency
    override suspend fun receive(): ByteArray? = try {
        runInterruptible(io) { NativeFraming.read(link.input, BridgeProtocol.MAX_FROM_BROWSER_BYTES) }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        log.w(e) { "Bridge connection stream failed" }
        null
    }

    @HighFrequency
    override suspend fun send(payload: ByteArray) {
        writeLock.withLock {
            try {
                runInterruptible(io) { NativeFraming.write(link.output, payload) }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw BridgeException(BridgeErrorCode.UNAVAILABLE, "Extension disconnected", e)
            }
        }
    }
}
