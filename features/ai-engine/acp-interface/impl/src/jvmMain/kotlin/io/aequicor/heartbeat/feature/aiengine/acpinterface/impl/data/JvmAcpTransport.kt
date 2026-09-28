package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean

internal class JvmAcpTransport(private val process: Process, private val dispatchers: DispatcherProvider) :
    AcpTransport {
    private val log = Log.tag("JvmAcpTransport")
    private val isClosed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val frames = Channel<String>(FRAME_BUFFER)
    private val writes = Mutex()

    init {
        scope.launch {
            try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                InputStreamReader(process.inputStream, decoder).buffered().use { reader ->
                    while (true) frames.send(reader.readFrame() ?: break)
                }
                frames.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(AcpDiagnostic(e)) { "ACP stdio read failed" }
                frames.close(AcpException.Disconnected())
            }
        }
    }

    override suspend fun receive(): String? {
        val result = frames.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()
    }

    override suspend fun send(frame: String) {
        require('\n' !in frame && '\r' !in frame && frame.length <= MAX_FRAME_CHARS) { "Invalid ACP frame" }
        try {
            writes.withLock {
                withContext(dispatchers.io) {
                    if (isClosed.get()) throw AcpException.Disconnected()
                    process.outputStream.write((frame + "\n").toByteArray(Charsets.UTF_8))
                    process.outputStream.flush()
                }
            }
        } finally {
            // Checked after releasing [writes]: close() may have set isClosed and failed tryLock while we held it.
            // Whichever side observes the lock free last closes stdin; closeStdin() is idempotent.
            if (isClosed.get()) closeStdin()
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        log.i { "ACP stopping stdio process" }
        // Destroy first: blocked pipe IO must unblock before the IO coroutine can finish.
        destroyDescendants()
        process.destroyForcibly()
        closeStdin()
        frames.close(AcpException.Disconnected())
        scope.cancel()
    }

    /**
     * Launchers (npx, uvx, cmd) leave the real agent as a child that inherits our pipes, so descendants alive at close
     * are stopped before the parent. Failure to enumerate them must not prevent stopping the parent.
     */
    private fun destroyDescendants() {
        try {
            process.descendants().toList().forEach { it.destroyForcibly() }
        } catch (e: SecurityException) {
            log.w(AcpDiagnostic(e)) { "ACP stdio descendants unavailable" }
        } catch (e: UnsupportedOperationException) {
            log.w(AcpDiagnostic(e)) { "ACP stdio descendants unsupported" }
        }
    }

    /**
     * Closing flushes under the stream monitor, so it must not race an in-flight write. Process death alone does not
     * release our pipe handle: a descendant may still hold the other end, and on Windows the handle lives until GC.
     * If a write holds [writes], it calls this again after releasing the lock because [isClosed] is already set.
     * Idempotent: closing an already closed stream is a no-op.
     */
    private fun closeStdin() {
        if (!writes.tryLock()) return
        try {
            process.outputStream.close()
        } catch (e: IOException) {
            log.w(AcpDiagnostic(e)) { "ACP stdio stdin close failed" }
        } finally {
            writes.unlock()
        }
    }

    private fun BufferedReader.readFrame(): String? {
        val value = StringBuilder()
        while (true) {
            when (val next = read()) {
                -1 -> {
                    if (value.isNotEmpty()) throw AcpException.Protocol()
                    return null
                }

                '\n'.code -> return value.toString().removeSuffix("\r")

                else -> {
                    if (value.length >= MAX_FRAME_CHARS) throw AcpException.Protocol()
                    value.append(next.toChar())
                }
            }
        }
    }

    private companion object {
        const val FRAME_BUFFER = 16
        const val MAX_FRAME_CHARS = 8 * 1024 * 1024
    }
}
