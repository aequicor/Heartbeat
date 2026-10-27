package io.aequicor.heartbeat.core.secrets.impl.data

import com.sun.jna.platform.win32.Crypt32Util
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.logging.desktopDataName
import io.aequicor.heartbeat.core.secrets.impl.SecretsConfig
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal class JvmProtectedVault(
    private val platform: PlatformInfo,
    private val config: SecretsConfig = SecretsConfig(),
) : ProtectedVault {
    // One cached view per vault: the Keychain is asked for a profile's snapshot once per launch, not per operation.
    private val keychain = CachedVault(
        object : ProtectedVault {
            override fun <T> transaction(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T =
                MacKeychain(config.service, config.isInteractionAllowed).transaction(profile, erase, action)
        },
    )

    override fun <T> transaction(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        val root = root()
        Files.createDirectories(root)
        // A separate stable lock file survives atomic replacement of the encrypted data file.
        FileChannel.open(
            root.resolve("$profile.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                return when (platform.host) {
                    HostPlatform.Windows -> windows(root.resolve("$profile.vault"), profile, erase, action)

                    HostPlatform.MacOs -> keychain.transaction(profile, erase, action)

                    HostPlatform.Linux -> LinuxKeyring(config.service).transaction(profile, erase, action)

                    HostPlatform.Android, HostPlatform.Ios -> error(
                        "Protected storage is unsupported on this platform",
                    )
                }
            }
        }
    }

    private fun root(): Path {
        config.directory?.let { return Path.of(it) }
        val home = System.getProperty("user.home")
        return when (platform.host) {
            HostPlatform.Windows -> Path.of(
                System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local",
                "Aequicor/${desktopDataName()}/secrets",
            )

            HostPlatform.MacOs -> Path.of(home, "Library/Application Support/${desktopDataName()}/secrets")

            HostPlatform.Linux -> Path.of(
                System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() && Path.of(it).isAbsolute }
                    ?: "$home/.local/share",
                "${desktopDataName()}/secrets",
            )

            HostPlatform.Android, HostPlatform.Ios -> error(
                "Protected storage is unsupported on this platform",
            )
        }
    }

    private fun <T> windows(file: Path, profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        val entropy = profile.toByteArray(Charsets.UTF_8)
        val old = if (!erase && Files.exists(
                file,
            )
        ) {
            Crypt32Util.cryptUnprotectData(Files.readAllBytes(file), entropy, 1, null)
        } else {
            null
        }
        try {
            val update = action(old)
            try {
                if (update.hasChanges) {
                    val bytes = update.bytes
                    if (bytes == null) {
                        Files.deleteIfExists(file)
                    } else {
                        val encrypted = Crypt32Util.cryptProtectData(bytes, entropy, 1, null, null)
                        atomicWrite(file, encrypted)
                    }
                }
                return update.result
            } finally {
                update.bytes?.fill(0)
            }
        } finally {
            old?.fill(0)
        }
    }

    private fun atomicWrite(file: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(file.parent, "vault-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
