package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Whether the managed Claude copy's executable still is the bytes the installation store extracted and activated.
 * The expected digest is [ManagedInstall.executableSha256], never the release archive digest. Null means the record
 * carries no executable digest, so provenance stays unknown instead of trusted or rejected. The file is hashed in
 * blocks with a cancellation check, and a missing or non-executable copy is a mismatch.
 */
internal suspend fun verifyClaudeManagedCopy(managed: ManagedInstall, io: CoroutineDispatcher): Boolean? {
    val expected = managed.executableSha256 ?: return null
    val path = try {
        Path.of(managed.executable)
    } catch (error: InvalidPathException) {
        Log.tag("ClaudeManagedDigest").w(error.redacted()) { "Selected runtime path is invalid" }
        return false
    }
    if (!Files.isRegularFile(path) || !Files.isExecutable(path)) return false
    val actual = claudeExecutableSha256(managed.executable, io) ?: return false
    return actual.equals(expected, ignoreCase = true)
}

/**
 * SHA-256 of the selected executable, or null when it cannot be read as one unchanged regular file. The file is
 * hashed in blocks with a cancellation check; a rewrite during hashing is an unobserved artifact, not a digest of
 * whatever bytes happened to be read. Paths and native errors never enter the log.
 */
internal suspend fun claudeExecutableSha256(executable: String, io: CoroutineDispatcher): String? =
    withContext(io) { executableDigest(executable) }

private suspend fun executableDigest(executable: String): String? = try {
    val path = Path.of(executable).toRealPath()
    val before = Files.readAttributes(path, BasicFileAttributes::class.java)
    if (!before.isRegularFile || before.size() > MAX_EXECUTABLE_BYTES) {
        null
    } else {
        val hashed = hashFile(path)
        val after = Files.readAttributes(path, BasicFileAttributes::class.java)
        if (hashed == null || !isUnchanged(before, after) || hashed.size != after.size()) null else hashed.digest
    }
} catch (error: IOException) {
    Log.tag("ClaudeManagedDigest").w(IllegalStateException(error.javaClass.simpleName)) {
        "Selected runtime digest unavailable"
    }
    null
} catch (error: SecurityException) {
    Log.tag("ClaudeManagedDigest").w(IllegalStateException(error.javaClass.simpleName)) {
        "Selected runtime digest was denied"
    }
    null
}

private suspend fun hashFile(path: Path): HashedFile? {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(READ_BLOCK_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_EXECUTABLE_BYTES) return null
            digest.update(buffer, 0, read)
        }
    }
    return HashedFile(total, HexFormat.of().formatHex(digest.digest()))
}

private fun isUnchanged(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
    before.fileKey() == after.fileKey() && before.size() == after.size() &&
        before.lastModifiedTime() == after.lastModifiedTime()

private data class HashedFile(val size: Long, val digest: String)

private const val MAX_EXECUTABLE_BYTES = 512L * 1024 * 1024
private const val READ_BLOCK_BYTES = 64 * 1024
