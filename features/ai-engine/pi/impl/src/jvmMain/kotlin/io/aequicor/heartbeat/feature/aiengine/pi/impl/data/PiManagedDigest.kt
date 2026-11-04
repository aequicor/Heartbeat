package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Whether the managed copy still is the artifact the installer verified against its pinned release digest.
 * Null means the record carries no digest, so provenance stays unknown instead of trusted or rejected.
 * The file is hashed in blocks with a cancellation check, and a missing or non-executable copy is a mismatch.
 */
internal suspend fun verifyPiManagedCopy(managed: ManagedInstall, io: CoroutineDispatcher): Boolean? {
    val expected = managed.sha256 ?: return null
    val path = try {
        Path.of(managed.executable)
    } catch (error: InvalidPathException) {
        Log.tag("PiManagedDigest").w(error.withoutDetails()) { "Selected runtime path is invalid" }
        return false
    }
    if (!Files.isRegularFile(path) || !Files.isExecutable(path)) return false
    return withContext(io) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(READ_BLOCK)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        HexFormat.of().formatHex(digest.digest()).equals(expected, ignoreCase = true)
    }
}

private const val READ_BLOCK = 1 shl 16
