package io.aequicor.heartbeat.core.secrets.impl.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CachedVaultTest {
    private val backend = CountingVault()
    private val vault = CachedVault(backend)

    @Test
    fun `a profile is read from the backend once`() {
        backend.stored["p"] = byteArrayOf(1, 2)

        repeat(3) {
            val seen = vault.transaction("p") { old -> VaultUpdate(null, false, old?.copyOf()) }
            assertContentEquals(byteArrayOf(1, 2), seen)
        }

        assertEquals(1, backend.reads)
    }

    @Test
    fun `a change is written and the next read sees it without reading the backend again`() {
        backend.stored["p"] = byteArrayOf(1)
        vault.transaction("p") { VaultUpdate(byteArrayOf(9, 9), true, Unit) }

        val seen = vault.transaction("p") { old -> VaultUpdate(null, false, old?.copyOf()) }

        assertContentEquals(byteArrayOf(9, 9), seen)
        assertContentEquals(byteArrayOf(9, 9), backend.stored["p"])
        assertEquals(1, backend.reads)
    }

    @Test
    fun `a write after a cached read does not read the backend`() {
        backend.stored["p"] = byteArrayOf(1)
        vault.transaction("p") { old -> VaultUpdate(null, false, old?.size) }
        vault.transaction("p") { VaultUpdate(byteArrayOf(2), true, Unit) }

        assertEquals(1, backend.reads)
        assertContentEquals(byteArrayOf(2), backend.stored["p"])
    }

    @Test
    fun `erasing drops the snapshot and the next read asks the backend`() {
        backend.stored["p"] = byteArrayOf(1)
        vault.transaction("p") { old -> VaultUpdate(null, false, old?.size) }
        vault.transaction("p", erase = true) { VaultUpdate(null, true, Unit) }

        val seen = vault.transaction("p") { old -> VaultUpdate(null, false, old) }

        assertNull(seen)
        assertEquals(2, backend.reads)
    }

    @Test
    fun `profiles are cached separately`() {
        backend.stored["a"] = byteArrayOf(1)
        backend.stored["b"] = byteArrayOf(2)

        vault.transaction("a") { old -> VaultUpdate(null, false, old?.size) }
        vault.transaction("b") { old -> VaultUpdate(null, false, old?.size) }
        vault.transaction("a") { old -> VaultUpdate(null, false, old?.size) }

        assertEquals(2, backend.reads)
    }

    @Test
    fun `an action that wipes its buffer does not corrupt the snapshot`() {
        backend.stored["p"] = byteArrayOf(5, 6)
        vault.transaction("p") { old ->
            old?.fill(0);
            VaultUpdate(null, false, Unit)
        }

        val seen = vault.transaction("p") { old -> VaultUpdate(null, false, old?.copyOf()) }

        assertContentEquals(byteArrayOf(5, 6), seen)
    }

    private class CountingVault : ProtectedVault {
        val stored = mutableMapOf<String, ByteArray>()
        var reads = 0

        override fun <T> transaction(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
            val old = if (erase) null else stored[profile]?.copyOf().also { reads++ }
            val update = action(old)
            if (update.hasChanges) {
                val bytes = update.bytes
                if (bytes == null) stored.remove(profile) else stored[profile] = bytes.copyOf()
            }
            update.bytes?.fill(0)
            old?.fill(0)
            return update.result
        }
    }
}
