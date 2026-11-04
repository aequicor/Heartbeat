package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.logging.Log
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Explicitly external fingerprint of the selected DSH executable. This is an observed, trust-on-first-use class
 * digest of the launcher file, **not** a pinned release digest and not proof of the whole execution closure: DSH
 * may resolve Electron, ASAR or native resources outside this file, and the user owns and updates it. An absent,
 * unreadable, non-regular, oversized or concurrently changing file leaves the fingerprint unknown, so admission
 * must refuse rather than claim sealed provenance. The `external:sha256:` prefix is the machine-visible marker of
 * this policy and is never produced for a Heartbeat-managed artifact.
 */
internal object DshExternalFingerprint {
    /** Machine-visible marker of an observed external executable, never a pinned release digest. */
    const val PREFIX: String = "external:sha256:"

    /** True only for a value this policy produces: the marker followed by 64 lowercase hex digits. */
    fun isExternal(value: String?): Boolean = value != null && EXTERNAL_PATTERN.matches(value)

    private val log = Log.tag("DshExternalFingerprint")

    /** Fingerprint of exactly [executable], or null when its bytes cannot be observed unambiguously. */
    fun of(executable: String, checkCancellation: () -> Unit = {}): String? = try {
        val path = Path.of(executable).toRealPath()
        val before = Files.readAttributes(path, BasicFileAttributes::class.java)
        if (!before.isRegularFile || before.size() <= 0L || before.size() > MAX_EXECUTABLE_BYTES) {
            null
        } else {
            val digest = hash(path, checkCancellation)
            val after = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (digest == null || !isUnchanged(before, after)) null else PREFIX + digest
        }
    } catch (error: IOException) {
        log.w(IllegalStateException(error.javaClass.simpleName)) { "Selected DSH executable could not be read" }
        null
    } catch (error: SecurityException) {
        log.w(IllegalStateException(error.javaClass.simpleName)) { "Selected DSH executable was denied" }
        null
    }

    private fun hash(path: Path, checkCancellation: () -> Unit): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(READ_BLOCK_BYTES)
            while (true) {
                checkCancellation()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_EXECUTABLE_BYTES) return null
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun isUnchanged(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        before.fileKey() == after.fileKey() && before.size() == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime()
}

private const val MAX_EXECUTABLE_BYTES = 512L * 1024 * 1024
private const val READ_BLOCK_BYTES = 64 * 1024
private val EXTERNAL_PATTERN = Regex("""external:sha256:[0-9a-f]{64}""")
