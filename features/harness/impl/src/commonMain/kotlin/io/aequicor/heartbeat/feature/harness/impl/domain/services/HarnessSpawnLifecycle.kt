package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessEffect

/** Revokes bounded script-helper generations; true confirms native release and journal settlement. */
internal fun interface HarnessSpawnLifecycle {
    suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean
}
