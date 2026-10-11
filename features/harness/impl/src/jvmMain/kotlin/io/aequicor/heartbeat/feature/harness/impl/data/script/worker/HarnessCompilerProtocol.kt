package io.aequicor.heartbeat.feature.harness.impl.data.script.worker

import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.data.script.MAX_HARNESS_DIAGNOSTICS
import io.aequicor.heartbeat.feature.harness.impl.data.script.MAX_HARNESS_DIAGNOSTIC_CHARS
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** First value the worker writes once it is ready; anything else on its output is not a compiler worker. */
internal const val HARNESS_COMPILER_WORKER_READY: Int = 0x48424b32

/**
 * One compilation for the worker: [source] of [kind], whose jar the worker writes to [output] — a temporary file the
 * host created in its cache directory and owns. The source is private code: never logged.
 */
internal data class HarnessCompilerRequest(val kind: HarnessCodeKind, val source: String, val output: String) {
    override fun toString(): String = "HarnessCompilerRequest(kind=$kind, ***)"
}

/** The worker's answer to one request; diagnostics are bounded UI data without exception objects. */
internal sealed interface HarnessCompilerReply {
    /** The jar is in the request's output file; [warnings] are the compiler's warnings. */
    data class Compiled(val warnings: List<HarnessCodeDiagnostic>) : HarnessCompilerReply

    /** The source does not compile; nothing was written to the output file. */
    data class Failed(val diagnostics: List<HarnessCodeDiagnostic>) : HarnessCompilerReply

    /** The compiler failed for a reason other than the source; the worker logged it and stays up. */
    data object Internal : HarnessCompilerReply
}

/**
 * Binary frames on the worker's standard streams, written and read by the same build of the app: enums travel as
 * ordinals (the release build's shrinker breaks lookups by name). Lengths, counts and ordinals are bounded on reading,
 * so a broken stream fails with an [IOException] instead of a huge allocation.
 */
internal fun DataOutputStream.writeRequest(request: HarnessCompilerRequest) {
    writeByte(FRAME_REQUEST)
    writeInt(request.kind.ordinal)
    writeText(request.source)
    writeText(request.output)
    flush()
}

/** The next request, or null when the host closed the stream. */
internal fun DataInputStream.readRequest(): HarnessCompilerRequest? {
    val frame = read()
    if (frame == END_OF_STREAM) return null
    if (frame != FRAME_REQUEST) throw IOException("Unexpected compiler worker frame $frame")
    val kind = HarnessCodeKind.entries[readCount(HarnessCodeKind.entries.lastIndex)]
    return HarnessCompilerRequest(kind, source = readText(MAX_SOURCE_BYTES), output = readText(MAX_PATH_BYTES))
}

internal fun DataOutputStream.writeReply(reply: HarnessCompilerReply) {
    when (reply) {
        is HarnessCompilerReply.Compiled -> {
            writeByte(REPLY_COMPILED)
            writeDiagnostics(reply.warnings)
        }

        is HarnessCompilerReply.Failed -> {
            writeByte(REPLY_FAILED)
            writeDiagnostics(reply.diagnostics)
        }

        HarnessCompilerReply.Internal -> writeByte(REPLY_INTERNAL)
    }
    flush()
}

internal fun DataInputStream.readReply(): HarnessCompilerReply = when (val kind = readUnsignedByte()) {
    REPLY_COMPILED -> HarnessCompilerReply.Compiled(readDiagnostics())
    REPLY_FAILED -> HarnessCompilerReply.Failed(readDiagnostics())
    REPLY_INTERNAL -> HarnessCompilerReply.Internal
    else -> throw IOException("Unexpected compiler worker reply $kind")
}

private fun DataOutputStream.writeDiagnostics(diagnostics: List<HarnessCodeDiagnostic>) {
    val bounded = diagnostics.take(MAX_HARNESS_DIAGNOSTICS)
    writeInt(bounded.size)
    bounded.forEach { diagnostic ->
        writeInt(diagnostic.severity.ordinal)
        writeText(diagnostic.message.take(MAX_HARNESS_DIAGNOSTIC_CHARS))
        writeInt(diagnostic.line ?: NO_POSITION)
        writeInt(diagnostic.column ?: NO_POSITION)
    }
}

private fun DataInputStream.readDiagnostics(): List<HarnessCodeDiagnostic> =
    List(readCount(MAX_HARNESS_DIAGNOSTICS)) { _ -> readDiagnostic() }

private fun DataInputStream.readDiagnostic(): HarnessCodeDiagnostic = HarnessCodeDiagnostic(
    severity = HarnessDiagnosticSeverity.entries[readCount(HarnessDiagnosticSeverity.entries.lastIndex)],
    message = readText(MAX_MESSAGE_BYTES),
    line = readInt().takeIf { it != NO_POSITION },
    column = readInt().takeIf { it != NO_POSITION },
)

private fun DataOutputStream.writeText(text: String) {
    val bytes = text.encodeToByteArray()
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readText(maxBytes: Int): String =
    ByteArray(readCount(maxBytes)).also(::readFully).decodeToString()

private fun DataInputStream.readCount(max: Int): Int =
    readInt().also { if (it !in 0..max) throw IOException("Compiler worker frame value $it is out of bounds") }

/** UTF-8 takes at most three bytes per UTF-16 character. */
private const val UTF8_MAX_BYTES = 3
private const val MAX_SOURCE_BYTES = HarnessLimits.SOURCE_CHARS * UTF8_MAX_BYTES
private const val MAX_MESSAGE_BYTES = MAX_HARNESS_DIAGNOSTIC_CHARS * UTF8_MAX_BYTES
private const val MAX_PATH_BYTES = 64 * 1024
private const val END_OF_STREAM = -1
private const val FRAME_REQUEST = 1
private const val REPLY_COMPILED = 1
private const val REPLY_FAILED = 2
private const val REPLY_INTERNAL = 3
private const val NO_POSITION = -1
