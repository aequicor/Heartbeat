package io.aequicor.heartbeat.core.secrets.impl

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.secrets.SecretStorageProtection
import io.aequicor.heartbeat.core.secrets.impl.data.JvmDevelopmentVault
import io.aequicor.heartbeat.core.secrets.impl.data.VaultUpdate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JvmDevelopmentVaultTest {
    private val mac = object : PlatformInfo {
        override val host = HostPlatform.MacOs
    }
    private val namespace = "a".repeat(64)

    @Test
    fun `local development backend requires explicit opt in`() {
        assertFailsWith<IllegalArgumentException> { JvmDevelopmentVault(mac, SecretsConfig()) }
    }

    @Test
    fun `local persistence never reads or changes production records`() = temporary { root ->
        val production = root.resolve("$namespace.vault")
        val productionBytes = "production sentinel".toByteArray()
        Files.write(production, productionBytes)
        val config = SecretsConfig(directory = root.toString(), isDevelopment = true)
        val first = JvmDevelopmentVault(mac, config)
        val bytes = "development fixture".toByteArray()
        assertEquals(SecretStorageProtection.LocalDevelopment, first.protection)
        first.transaction(namespace) {
            assertNull(it)
            VaultUpdate(bytes.copyOf(), true, Unit)
        }
        JvmDevelopmentVault(mac, config).transaction(namespace) {
            assertContentEquals(bytes, it)
            VaultUpdate(null, false, Unit)
        }
        first.transaction("b".repeat(64)) {
            assertNull(it)
            VaultUpdate(null, false, Unit)
        }
        assertContentEquals(productionBytes, Files.readAllBytes(production))
        val stored = root.resolve("development/$namespace.vault")
        if ("posix" in root.fileSystem.supportedFileAttributeViews()) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(stored))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(stored.parent))
        }
    }

    @Test
    fun `failed transaction preserves prior snapshot and wipe does not decode corrupt bytes`() = temporary { root ->
        val vault = JvmDevelopmentVault(mac, SecretsConfig(directory = root.toString(), isDevelopment = true))
        val bytes = byteArrayOf(4, 5, 6)
        vault.transaction(namespace) { VaultUpdate(bytes.copyOf(), true, Unit) }
        assertFailsWith<IllegalStateException> {
            vault.transaction<Unit>(namespace) { error("synthetic transaction failure") }
        }
        vault.transaction(namespace) {
            assertContentEquals(bytes, it)
            VaultUpdate(null, false, Unit)
        }
        vault.transaction(namespace, erase = true) {
            assertNull(it)
            VaultUpdate(null, true, Unit)
        }
        assertFalse(Files.exists(root.resolve("development/$namespace.vault")))
    }

    @Test
    fun `read and written buffers are cleared after the transaction`() = temporary { root ->
        val vault = JvmDevelopmentVault(mac, SecretsConfig(directory = root.toString(), isDevelopment = true))
        val written = byteArrayOf(1, 2, 3)
        vault.transaction(namespace) { VaultUpdate(written, true, Unit) }
        assertContentEquals(ByteArray(3), written)
        val read = vault.transaction(namespace) { VaultUpdate(null, false, requireNotNull(it)) }
        assertContentEquals(ByteArray(3), read)
    }

    private fun temporary(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("heartbeat-development-vault")
        try {
            action(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
