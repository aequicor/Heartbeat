package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider

/**
 * Upgrades legacy `provider/model` entries only when the provider has one fixed origin. Editable routes have no
 * recoverable origin in legacy data, so their old entries stay unused. Explicit route entries take precedence.
 * Model ids may contain slashes; remove only the known provider prefix instead of splitting the whole key.
 */
internal fun KoogReasoningState.withRouteOrigins(): KoogReasoningState {
    var result = this
    KoogProvider.entries.filterNot { it.isOriginEditable }.forEach { provider ->
        val prefix = "${provider.id.value}/"
        val routePrefix = "$prefix${provider.origin.value}/"
        val oldLevels = result.levels.filterKeys { it.startsWith(prefix) && !it.startsWith(routePrefix) }
        val oldRejected = result.rejected.filter { it.startsWith(prefix) && !it.startsWith(routePrefix) }.toSet()
        val migratedLevels = oldLevels.mapKeys { routePrefix + it.key.removePrefix(prefix) }
        val migratedRejected = oldRejected.map { routePrefix + it.removePrefix(prefix) }
            .filterNot { it in result.levels }
        result = result.copy(
            levels = migratedLevels + (result.levels - oldLevels.keys),
            rejected = (result.rejected - oldRejected) + migratedRejected,
        )
    }
    return result
}
