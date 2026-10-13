package io.aequicor.heartbeat.buildlogic

import org.gradle.api.logging.Logger
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A pinned download verified by SHA-256 and kept in a persistent cache (Gradle user home, outside `build/`), so clean
 * builds and `--offline` runs reuse it without the network. A missing or mismatching cached file is downloaded into
 * a temporary file next to it and atomically moved into place; in offline mode the build fails instead.
 */
internal class VerifiedDownload(private val logger: Logger, private val offline: Boolean) {

    /** [target] holding the content of [url] with [sha256]; [label] names the file in logs and errors. */
    fun fetch(url: String, sha256: String, target: File, label: String): File {
        if (target.isFile) {
            val actual = target.sha256()
            if (actual == sha256) return target
            logger.warn("Cached $label $target has SHA-256 $actual, expected $sha256; re-downloading")
        }
        check(!offline) {
            "$label is not cached in ${target.parentFile} and Gradle runs --offline; run once online to populate the cache"
        }
        val directory = target.parentFile
        directory.mkdirs()
        logger.lifecycle("Downloading $label from $url")
        val partial = File.createTempFile(target.name, ".part", directory)
        try {
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            connection.getInputStream().use { input -> partial.outputStream().use(input::copyTo) }
            val actual = partial.sha256()
            check(actual == sha256) { "$label checksum mismatch: expected $sha256, actual $actual; refusing to use it" }
            moveIntoPlace(partial, target)
        } finally {
            if (partial.exists() && !partial.delete()) logger.warn("Cannot delete temporary download $partial")
        }
        return target
    }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            logger.info("Atomic move is not supported for $target; falling back to replace", e)
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw IOException("Cannot move download into cache $target", e)
        }
    }

    private fun File.sha256(): String {
        val hash = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
        const val BUFFER_BYTES = 65_536
    }
}
