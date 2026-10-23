package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.Flow

/**
 * Profile-wide signal for a host without a permission surface. It carries no identifiers, requests or handles,
 * so it can cross a bundle boundary.
 */
public interface NativeInteractionDemand {
    /** True while an open session awaits a user action or the profile needs a host authentication intervention. */
    public val required: Flow<Boolean>
}
