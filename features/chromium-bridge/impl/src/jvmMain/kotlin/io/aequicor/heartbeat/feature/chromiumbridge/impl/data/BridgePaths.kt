package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

/** Location of the app socket the native-messaging host connects to. */
internal object BridgePaths {
    /** File name of the socket inside its private directory. */
    const val SOCKET_NAME = "bridge.sock"

    // sun_path is 104 bytes on macOS and 108 on Windows, including the terminator.
    private const val MAX_SOCKET_PATH_BYTES = 100
    private const val FALLBACK_DIGEST_CHARS = 12
    private const val OWNER_ONLY = "rwx------"

    /**
     * `<base>/run/bridge.sock`, or a per-user temporary directory keyed by that path when it is too long for a
     * Unix domain socket address. [base] is the bridge's application files directory.
     */
    fun socket(base: Path): Path {
        val preferred = base.resolve("run").resolve(SOCKET_NAME).toAbsolutePath().normalize()
        if (preferred.toString().toByteArray(Charsets.UTF_8).size <= MAX_SOCKET_PATH_BYTES) return preferred
        val digest = HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(preferred.toString().toByteArray(Charsets.UTF_8)))
            .take(FALLBACK_DIGEST_CHARS)
        return Path.of(System.getProperty("java.io.tmpdir")).resolve("heartbeat-$digest").resolve(SOCKET_NAME)
            .toAbsolutePath().normalize()
    }

    /** Creates [directory] readable only by the current user where the file system has POSIX permissions. */
    fun ensurePrivateDirectory(directory: Path) {
        Files.createDirectories(directory)
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(OWNER_ONLY))
        }
    }
}
