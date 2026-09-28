package io.aequicor.heartbeat.feature.aiengine.authenticator.impl.data

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.secrets.SecretUsage
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** In-memory [SecretStore] with scripted failures. */
private class FakeSecretStore : SecretStore {
    val values = mutableMapOf<SecretKey, String>()
    val bindings = mutableMapOf<SecretUsage, SecretKey>()
    var bindFailure: Exception? = null

    override suspend fun write(key: SecretKey, value: Secret) {
        values[key] = value.reveal { it.concatToString() }
    }

    override suspend fun read(key: SecretKey): Secret? = values[key]?.let { Secret(it.toCharArray()) }

    override suspend fun keys(): List<SecretKey> = values.keys.toList()

    override suspend fun bind(usage: SecretUsage, key: SecretKey?) {
        bindFailure?.let { throw it }
        if (key == null) bindings -= usage else bindings[usage] = key
    }

    override suspend fun readFor(usage: SecretUsage): Secret? = bindings[usage]?.let { read(it) }

    override suspend fun usages(key: SecretKey): List<SecretUsage> = bindings.filterValues { it == key }.keys.toList()

    override suspend fun remove(key: SecretKey): SecretRemoval {
        val used = usages(key)
        return when {
            used.isNotEmpty() -> SecretRemoval.InUse(used)
            values.remove(key) == null -> SecretRemoval.Missing
            else -> SecretRemoval.Removed
        }
    }
}

class SecretStoreKeyVaultTest {
    private val secrets = FakeSecretStore()
    private val vault = SecretStoreKeyVault(secrets)
    private val slot = AuthSecretId("aiengine_auth_t1")

    @Test
    fun `a failed bind of a new value leaves no value behind`() = runTest {
        secrets.bindFailure = IllegalStateException("backend")

        assertFailsWith<IllegalStateException> { vault.store(slot, Secret("v".toCharArray())) }

        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun `removal of a value still referenced elsewhere fails and keeps it`() = runTest {
        vault.store(slot, Secret("v".toCharArray()))
        secrets.bindings[SecretUsage("other", "x", "key")] = SecretKey(slot.value)

        assertFailsWith<ManagedKeyInUseException> { vault.remove(slot) }

        assertTrue(vault.contains(slot))
        assertEquals("v", secrets.values[SecretKey(slot.value)])
    }

    @Test
    fun `removal unbinds and deletes an unused value`() = runTest {
        vault.store(slot, Secret("v".toCharArray()))
        vault.remove(slot)
        assertFalse(vault.contains(slot))
    }
}
