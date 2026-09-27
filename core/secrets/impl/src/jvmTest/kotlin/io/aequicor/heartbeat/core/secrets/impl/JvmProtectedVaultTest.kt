package io.aequicor.heartbeat.core.secrets.impl

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.secrets.impl.data.JvmProtectedVault
import io.aequicor.heartbeat.core.secrets.impl.data.VaultUpdate
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JvmProtectedVaultTest {
    @Test
    fun nativeVaultPersistsAcrossInstancesAndWipesWithoutReadingCorruptCiphertext() {
        val os = System.getProperty("os.name")
        assumeTrue(os.startsWith("Windows") || os.startsWith("Mac"))
        val host = if (os.startsWith("Windows")) HostPlatform.Windows else HostPlatform.MacOs
        val platform = object : PlatformInfo {
            override val host = host
        }
        val root = Files.createTempDirectory("heartbeat-secrets-test")
        val config = SecretsConfig("heartbeat.test." + UUID.randomUUID(), root.toString())
        val namespace = "a".repeat(64)
        val other = "b".repeat(64)
        val bytes = "sensitive-native-value".toByteArray()
        val vault = JvmProtectedVault(platform, config)
        try {
            vault.transaction(namespace) { VaultUpdate(bytes.copyOf(), true, Unit) }
            JvmProtectedVault(platform, config).transaction(namespace) {
                assertContentEquals(bytes, it)
                VaultUpdate(null, false, Unit)
            }
            if (host == HostPlatform.Windows) {
                val file = root.resolve("$namespace.vault")
                assertFalse(Files.readAllBytes(file).toString(Charsets.UTF_8).contains("sensitive-native-value"))
                Files.copy(file, root.resolve("$other.vault"))
                assertFails { vault.transaction(other) { VaultUpdate(null, false, Unit) } }
                Files.write(file, byteArrayOf(1, 2, 3))
                assertFails { vault.transaction(namespace) { VaultUpdate(null, false, Unit) } }
            }
            vault.transaction(namespace, erase = true) { VaultUpdate(null, true, Unit) }
            vault.transaction(namespace) {
                assertNull(it)
                VaultUpdate(null, false, Unit)
            }
        } finally {
            vault.transaction(namespace, erase = true) { VaultUpdate(null, true, Unit) }
            vault.transaction(other, erase = true) { VaultUpdate(null, true, Unit) }
            root.toFile().deleteRecursively()
        }
    }
}
