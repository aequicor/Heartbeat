package io.aequicor.heartbeat.core.secrets.impl.domain

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.secrets.SecretUsage

internal class ProfileSecretStore(private val repository: SecretRepository) : SecretStore {
    override suspend fun write(key: SecretKey, value: Secret) {
        repository.transaction(hasChanges = true) { state ->
            val previous = state.values.put(key, value.reveal { it.copyOf() })
            previous?.fill('\u0000')
        }
    }

    override suspend fun read(key: SecretKey): Secret? =
        repository.transaction { state -> state.values[key]?.let(::Secret) }

    override suspend fun keys(): List<SecretKey> = repository.transaction { it.values.keys.toList() }

    override suspend fun bind(usage: SecretUsage, key: SecretKey?) {
        repository.transaction(hasChanges = true) { state ->
            require(key == null || key in state.values) { "Missing secret" }
            if (key == null) state.references.remove(usage) else state.references[usage] = key
        }
    }

    override suspend fun readFor(usage: SecretUsage): Secret? = repository.transaction { state ->
        state.references[usage]?.let { state.values[it] }?.let(::Secret)
    }

    override suspend fun usages(key: SecretKey): List<SecretUsage> =
        repository.transaction { state -> state.references.filterValues { it == key }.keys.toList() }

    override suspend fun remove(key: SecretKey): SecretRemoval = repository.transaction(hasChanges = true) { state ->
        val usages = state.references.filterValues { it == key }.keys.toList()
        when {
            usages.isNotEmpty() -> SecretRemoval.InUse(usages)
            state.values.remove(key)?.also { it.fill('\u0000') } != null -> SecretRemoval.Removed
            else -> SecretRemoval.Missing
        }
    }
}
