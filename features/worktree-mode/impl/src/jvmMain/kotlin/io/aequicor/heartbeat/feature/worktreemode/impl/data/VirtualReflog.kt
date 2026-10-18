package io.aequicor.heartbeat.feature.worktreemode.impl.data

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Append cursor with a tail fingerprint detects resets even when a later commit hides their last message. */
@Serializable
internal data class ReflogCursor(val size: Long, val tail: String)

internal data class ReflogDelta(val cursor: ReflogCursor?, val requiresManual: Boolean)

/** Rewritten, truncated, missing, or excessively large logs require explicit continuation. */
internal fun advanceReflog(path: String, previous: ReflogCursor?): ReflogDelta {
    val file = Path.of(path)
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return ReflogDelta(null, previous != null)
    return FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        val size = channel.size()
        val cursor = ReflogCursor(size, channel.fingerprint(size))
        val isRewritten = previous != null &&
            (previous.size > size || channel.fingerprint(previous.size) != previous.tail)
        val added = size - (previous?.size ?: size)
        val isUnbounded = added !in 0..MAX_REFLOG_DELTA
        val reset = if (previous == null || isRewritten || isUnbounded) {
            false
        } else {
            channel.readRange(previous.size, added.toInt()).decodeToString().lineSequence().any { line ->
                val action = line.substringAfter('\t', "")
                action.startsWith("reset:") || action.startsWith("checkout:")
            }
        }
        ReflogDelta(cursor, isRewritten || isUnbounded || reset)
    }
}

private fun FileChannel.fingerprint(end: Long): String {
    val length = end.coerceAtMost(REFLOG_TAIL_BYTES).toInt()
    return MessageDigest.getInstance("SHA-256").digest(readRange(end - length, length))
        .joinToString("") { "%02x".format(it) }
}

private fun FileChannel.readRange(start: Long, length: Int): ByteArray {
    val buffer = ByteBuffer.allocate(length)
    var offset = start
    while (buffer.hasRemaining()) {
        val read = read(buffer, offset)
        check(read > 0) { "SandboxReflogChanged" }
        offset += read
    }
    return buffer.array()
}

private const val MAX_REFLOG_DELTA = 2 * 1024 * 1024L
private const val REFLOG_TAIL_BYTES = 512L
