package io.aequicor.heartbeat.core.secrets.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.secrets.SecretStorageProtection
import io.aequicor.heartbeat.core.secrets.impl.SecretsConfig
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Explicit desktop development storage. It never calls Keychain/DPAPI or reads the production namespace.
 * Snapshots are local plaintext files with atomic replacement and private POSIX permissions when supported.
 * The shared vault registry still owns lifecycle, logging, profile isolation, references and buffer cleanup.
 */
internal class JvmDevelopmentVault(platform: PlatformInfo, config: SecretsConfig) : ProtectedVault {
    override val protection: SecretStorageProtection = SecretStorageProtection.LocalDevelopment
    private val root = developmentRoot(platform, config)
    private val hasPosix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    init {
        require(config.isDevelopment) { "Local credential storage requires a development host" }
    }

    override fun <T> transaction(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        require(profile.matches(Regex("[a-f0-9]{64}"))) { "Invalid profile namespace" }
        Files.createDirectories(root)
        if (hasPosix) Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        val lock = root.resolve("$profile.lock")
        FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            if (hasPosix) Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-------"))
            channel.lock().use { return access(root.resolve("$profile.vault"), erase, action) }
        }
    }

    private fun <T> access(file: Path, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        val previous = if (!erase && Files.exists(file)) Files.readAllBytes(file) else null
        try {
            val update = action(previous)
            try {
                if (update.hasChanges) {
                    val bytes = update.bytes
                    if (bytes == null) Files.deleteIfExists(file) else write(file, bytes)
                }
                return update.result
            } finally {
                update.bytes?.fill(0)
            }
        } finally {
            previous?.fill(0)
        }
    }

    private fun write(file: Path, bytes: ByteArray) {
        val temporary = if (hasPosix) {
            Files.createTempFile(
                root,
                "vault-",
                ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        } else {
            Files.createTempFile(root, "vault-", ".tmp")
        }
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun developmentRoot(platform: PlatformInfo, config: SecretsConfig): Path {
    config.directory?.let { return Path.of(it, "development") }
    val home = System.getProperty("user.home")
    return when (platform.host) {
        HostPlatform.MacOs -> Path.of(home, "Library/Application Support/Heartbeat/development-secrets")

        HostPlatform.Windows -> Path.of(
            System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local",
            "Aequicor/Heartbeat/development-secrets",
        )

        HostPlatform.Linux -> Path.of(home, ".local/share/Heartbeat/development-secrets")

        HostPlatform.Android, HostPlatform.Ios -> error("Desktop development storage is unavailable")
    }
}
