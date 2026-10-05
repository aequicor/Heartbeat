package io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker

import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlDiagramType
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlLimits
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlSource
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** First value the worker writes once it is ready; anything else on its output is not a worker. */
internal const val PLANTUML_WORKER_READY: Int = 0x48425055

/** One drawing for the worker: the prepared [source], the host's [preamble] and the image limits it applies. */
internal data class PlantUmlWorkerRequest(
    val source: PlantUmlSource,
    val preamble: List<String>,
    val limits: PlantUmlLimits,
)

/** The worker's answer: the drawing [result] and the warnings and errors the engine logged while drawing. */
internal data class PlantUmlWorkerReply(val result: PlantUmlResult, val diagnostics: List<PlantUmlWorkerDiagnostic>)

/** A record the worker logged at [level] (warning or error) under [tag]; [message] includes its stack trace. */
internal data class PlantUmlWorkerDiagnostic(val level: LogLevel, val tag: String, val message: String)

/**
 * Binary frames on the worker's standard streams, written and read by the same build of the app: enums travel as
 * ordinals (the release build's shrinker breaks lookups by name). Lengths and ordinals are bounded on reading, so a
 * broken stream fails with an [IOException] instead of a huge allocation; texts of the worker (messages that may
 * quote the source, diagnostics) are cut to fit those bounds when written.
 */
internal fun DataOutputStream.writeRequest(request: PlantUmlWorkerRequest) {
    writeByte(FRAME_REQUEST)
    writeText(request.source.text)
    writeInt(request.source.type.ordinal)
    writeInt(request.source.startLine)
    writeInt(request.source.addedLines)
    writeInt(request.preamble.size)
    request.preamble.forEach(::writeText)
    writeInt(request.limits.maxSide)
    writeLong(request.limits.maxPixels)
    writeInt(request.limits.maxPngBytes)
    flush()
}

/** The next request, or null when the host closed the stream. */
internal fun DataInputStream.readRequest(): PlantUmlWorkerRequest? {
    val frame = read()
    if (frame == END_OF_STREAM) return null
    if (frame != FRAME_REQUEST) throw IOException("Unexpected worker frame $frame")
    val text = readText(MAX_SOURCE_BYTES)
    val type = PlantUmlDiagramType.entries[readCount(PlantUmlDiagramType.entries.lastIndex)]
    val source = PlantUmlSource(text, type, startLine = readInt(), addedLines = readInt())
    val preamble = List(readCount(MAX_PREAMBLE_LINES)) { readText(MAX_TEXT_BYTES) }
    val limits = PlantUmlLimits(maxSide = readInt(), maxPixels = readLong(), maxPngBytes = readInt())
    return PlantUmlWorkerRequest(source, preamble, limits)
}

internal fun DataOutputStream.writeReply(reply: PlantUmlWorkerReply) {
    when (val result = reply.result) {
        is PlantUmlResult.Image -> {
            writeByte(RESULT_IMAGE)
            writeInt(result.png.size)
            write(result.png)
            writeFloat(result.width)
            writeFloat(result.height)
            writeFloat(result.scale)
        }

        is PlantUmlResult.SyntaxError -> {
            writeByte(RESULT_SYNTAX_ERROR)
            writeInt(result.line ?: NO_LINE)
            writeBoundedText(result.message)
        }

        is PlantUmlResult.Failed -> {
            writeByte(RESULT_FAILED)
            writeInt(result.reason.ordinal)
        }

        PlantUmlResult.Unsupported -> writeByte(RESULT_UNSUPPORTED)
    }
    val diagnostics = reply.diagnostics.take(MAX_DIAGNOSTICS)
    writeInt(diagnostics.size)
    diagnostics.forEach { diagnostic ->
        writeInt(diagnostic.level.ordinal)
        writeBoundedText(diagnostic.tag)
        writeBoundedText(diagnostic.message)
    }
    flush()
}

/** Reads a reply whose image may not exceed [maxPngBytes]. */
internal fun DataInputStream.readReply(maxPngBytes: Int): PlantUmlWorkerReply {
    val result = when (val kind = readUnsignedByte()) {
        RESULT_IMAGE -> {
            val png = ByteArray(readCount(maxPngBytes)).also(::readFully)
            PlantUmlResult.Image(png, width = readFloat(), height = readFloat(), scale = readFloat())
        }

        RESULT_SYNTAX_ERROR -> PlantUmlResult.SyntaxError(
            line = readInt().takeIf { it != NO_LINE },
            message = readText(MAX_TEXT_BYTES),
        )

        RESULT_FAILED -> PlantUmlResult.Failed(PlantUmlFailure.entries[readCount(PlantUmlFailure.entries.lastIndex)])

        RESULT_UNSUPPORTED -> PlantUmlResult.Unsupported

        else -> throw IOException("Unexpected worker result $kind")
    }
    val diagnostics = List(readCount(MAX_DIAGNOSTICS)) {
        PlantUmlWorkerDiagnostic(
            level = LogLevel.entries[readCount(LogLevel.entries.lastIndex)],
            tag = readText(MAX_TEXT_BYTES),
            message = readText(MAX_TEXT_BYTES),
        )
    }
    return PlantUmlWorkerReply(result, diagnostics)
}

private fun DataOutputStream.writeText(text: String) {
    val bytes = text.encodeToByteArray()
    writeInt(bytes.size)
    write(bytes)
}

/** UTF-8 takes at most three bytes per UTF-16 character, so the cut text always fits [MAX_TEXT_BYTES]. */
private fun DataOutputStream.writeBoundedText(text: String) = writeText(text.take(MAX_TEXT_BYTES / UTF8_MAX_BYTES))

private fun DataInputStream.readText(maxBytes: Int): String =
    ByteArray(readCount(maxBytes)).also(::readFully).decodeToString()

private fun DataInputStream.readCount(max: Int): Int =
    readInt().also { if (it !in 0..max) throw IOException("Worker frame value $it is out of bounds") }

/** Diagnostics the worker sends per reply, and the byte length of each text. */
internal const val MAX_DIAGNOSTICS = 4
internal const val MAX_TEXT_BYTES = 16 * 1024
private const val UTF8_MAX_BYTES = 3
private const val MAX_SOURCE_BYTES = 1024 * 1024
private const val MAX_PREAMBLE_LINES = 256
private const val END_OF_STREAM = -1
private const val FRAME_REQUEST = 1
private const val RESULT_IMAGE = 1
private const val RESULT_SYNTAX_ERROR = 2
private const val RESULT_FAILED = 3
private const val RESULT_UNSUPPORTED = 4
private const val NO_LINE = -1
