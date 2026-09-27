package io.aequicor.heartbeat.core.secrets.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.impl.domain.SecretRepository
import io.aequicor.heartbeat.core.secrets.impl.domain.SecretSnapshot

internal class ProfileSecretRepository(
    private val registry: VaultRegistry,
    private val profile: ProfileId,
    private val checkOpen: () -> Unit,
) : SecretRepository {
    override suspend fun <T> transaction(hasChanges: Boolean, action: (SecretSnapshot) -> T): T {
        Log.tag("SEC").d { "vault transaction requested" }
        return registry.access(profile, if (hasChanges) "update" else "read", hasChanges, checkOpen, action)
    }
}
