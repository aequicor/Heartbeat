package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigManifests
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteException
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteFailure
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.uuid.Uuid

private val log = Log.tag("ConfigFiles")

/** Attributes of [path] itself (a link is not followed), or null when it does not exist. */
internal fun attributesOrNull(path: Path): BasicFileAttributes? {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
    return try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (e: IOException) {
        log.w(e.redactedFailure()) { "Configuration file attributes are unreadable" }
        null
    }
}

/** [bytes] as strict UTF-8 in the normalized form of the tree, or null when they are not UTF-8 text. */
internal fun decodeConfigText(bytes: ByteArray): String? = try {
    val text = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    ConfigManifests.normalized(text)
} catch (e: CharacterCodingException) {
    log.w(e.redactedFailure()) { "A configuration file is not UTF-8 text" }
    null
}

/** All bytes of this stream, or null as soon as there are more than [limit]; declared sizes are not trusted. */
internal fun InputStream.readAtMost(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        total += read
        if (total > limit) return null
        out.write(buffer, 0, read)
    }
}

/**
 * Writes [bytes] to [target] through a hidden temporary file in the same directory and an atomic replace, so readers
 * see either the old or the new file. The temporary name starts with a dot: tree readers skip it.
 */
internal fun replaceAtomically(target: Path, bytes: ByteArray) {
    val parent = target.parent ?: throw ConfigWriteException(ConfigWriteFailure.Unavailable)
    val temporary = parent.resolve(".${target.fileName}.${Uuid.random().toHexString()}.tmp")
    try {
        Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            log.w(e.redactedFailure()) { "Atomic replace is unsupported here, replacing in place" }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: IOException) {
        throw ConfigWriteException(ConfigWriteFailure.Io, e)
    } finally {
        try {
            Files.deleteIfExists(temporary)
        } catch (e: IOException) {
            log.w(e.redactedFailure()) { "Temporary configuration file was not removed" }
        }
    }
}

private const val BUFFER = 8192
