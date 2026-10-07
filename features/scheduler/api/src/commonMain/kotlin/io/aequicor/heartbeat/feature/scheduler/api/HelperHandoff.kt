package io.aequicor.heartbeat.feature.scheduler.api

import kotlinx.serialization.Serializable

/**
 * Immutable host-created context for a helper's first prompt or an explicit recovery attempt. The initiator
 * carries restrictions, not permission or helper ownership. Feature context is opaque, bounded and never shown
 * to the model. The host journals it before opening a native session and gives it to prompt-admission owners
 * once the exact target reference exists. Null legacy context must never be reconstructed from prompt text.
 */
@Serializable
public data class HelperHandoff(
    val initiator: RequestInitiator? = null,
    val ownerFeature: String? = null,
    val ownerContext: String? = null,
) {
    init {
        require(ownerFeature == null || isValidEventFeatureName(ownerFeature)) { "Invalid helper feature owner" }
        require(ownerContext == null || ownerFeature != null) { "Helper context requires a feature owner" }
        require((ownerContext?.length ?: 0) <= SchedulerLimits.MAX_OWNER_CONTEXT) { "Helper context is too long" }
    }

    override fun toString(): String = "HelperHandoff(***)"
}
