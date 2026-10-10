package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.atomic.AtomicBoolean

/** One operation's JSONL pipes. send acknowledges flush; receive has exactly one consumer. */
internal interface ClaudeDuplex {
    suspend fun send(frame: JsonObject)
    suspend fun receive(): JsonObject?
    suspend fun closeInput()
}

/** Bounded queues separate blocking pipes from the protocol's suspending handlers. */
internal class ClaudeDuplexPipe(scope: CoroutineScope, input: BufferedWriter, output: BufferedReader) : ClaudeDuplex {
    private val writes = Channel<Write>(QUEUE_SIZE)
    private val frames = Channel<JsonObject>(QUEUE_SIZE)
    private val isClosed = AtomicBoolean(false)
    private val isInputClosed = AtomicBoolean(false)
    private val inputClosed = CompletableDeferred<Unit>()

    val writer = scope.async {
        try {
            for (write in writes) {
                // An answer revoked while waiting in the queue must never reach the process.
                if (write.ack.isActive) {
                    currentCoroutineContext().ensureActive()
                    input.write(write.text)
                    input.newLine()
                    input.flush()
                    write.ack.complete(Unit)
                }
            }
            if (isInputClosed.get() && !isClosed.get()) {
                input.close()
                inputClosed.complete(Unit)
            }
        } finally {
            inputClosed.completeExceptionally(unavailable())
            writes.close()
            while (true) {
                val write = writes.tryReceive().getOrNull() ?: break
                write.ack.completeExceptionally(unavailable())
            }
        }
    }

    val reader = scope.async {
        try {
            while (true) {
                val line = output.readFrame() ?: break
                currentCoroutineContext().ensureActive()
                if (line.isNotBlank()) {
                    val frame = parseFrame(line)
                    // Backpressure must not leave a control cancellation hidden behind an event backlog.
                    if (!frames.trySend(frame).isSuccess) protocolFailure()
                }
            }
        } finally {
            frames.close()
        }
    }

    override suspend fun send(frame: JsonObject) {
        if (isClosed.get() || isInputClosed.get()) throw unavailable()
        val text = frame.toString()
        if (text.length > MAX_FRAME_CHARS) protocolFailure()
        enqueue(text)
    }

    override suspend fun receive(): JsonObject? {
        currentCoroutineContext().ensureActive()
        val received = frames.receiveCatching()
        received.exceptionOrNull()?.let { throw it }
        return received.getOrNull()
    }

    override suspend fun closeInput() {
        if (isClosed.get()) return
        // Closing the queue is the EOF commitment. It survives cancellation of any individual waiter.
        isInputClosed.set(true)
        writes.close()
        inputClosed.await()
    }

    /** Nonblocking revocation. The owner kills the process before joining either blocked pipe job. */
    fun revoke() {
        isClosed.set(true)
        writes.close()
        frames.cancel()
        writer.cancel()
        reader.cancel()
    }

    private suspend fun enqueue(text: String) {
        val ack = CompletableDeferred<Unit>(currentCoroutineContext()[Job])
        try {
            writes.send(Write(text, ack))
            ack.await()
        } finally {
            ack.cancel()
        }
    }

    private data class Write(val text: String, val ack: CompletableDeferred<Unit>) {
        override fun toString(): String = "ClaudeWrite(redacted)"
    }
}

private fun parseFrame(line: String): JsonObject = try {
    Json.parseToJsonElement(line) as? JsonObject ?: protocolFailure()
} catch (e: SerializationException) {
    // A parser exception quotes the prompt; expose only the existing typed protocol failure.
    Log.tag("ClaudeDuplex").w(e.redacted()) { "Malformed Claude duplex frame" }
    throw EngineException(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
}

private fun unavailable() = EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
private const val QUEUE_SIZE = 64
