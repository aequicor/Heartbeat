package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretRemoval
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.configSecretUsage
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ConfigSecrets

/** [ConfigSecrets] in the profile vault: one key per engine slot, bound to its configuration usage. */
class VaultConfigSecrets(private val store: SecretStore) : ConfigSecrets {
    private val log = Log.tag("ConfigSecrets")

    override suspend fun put(engine: EngineId, slot: String, value: Secret) {
        val usage = configSecretUsage(engine, slot)
        val key = SecretKey("engcfg_${usage.id}_$slot")
        store.write(key, value)
        store.bind(usage, key)
        log.i { "configuration secret stored engine=${engine.value}" }
    }

    override suspend fun remove(engine: EngineId, slot: String) {
        val usage = configSecretUsage(engine, slot)
        store.bind(usage, null)
        when (val removal = store.remove(SecretKey("engcfg_${usage.id}_$slot"))) {
            SecretRemoval.Removed, SecretRemoval.Missing -> log.i {
                "configuration secret removed engine=${engine.value}"
            }

            is SecretRemoval.InUse -> log.w { "configuration secret kept, still used=${removal.usages.size}" }
        }
    }
}
