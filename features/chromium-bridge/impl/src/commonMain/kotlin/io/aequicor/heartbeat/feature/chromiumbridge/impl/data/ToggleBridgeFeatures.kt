package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeAgentControl
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEnabled
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFeatureSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** Bridge features the app offers to extensions, from the rollout toggles. */
internal class ToggleBridgeFeatures(private val toggles: FeatureToggles) {
    /** Current features; extensions receive changes as `app.features`. */
    fun observe(): Flow<BridgeFeatureSet> = combine(
        toggles.observe(ChromiumBridgeEnabled),
        toggles.observe(ChromiumBridgeAgentControl),
    ) { isEnabled, isAgentControl ->
        // The side-panel chat arrives with the profile chat service; until then only agent control is offered.
        BridgeFeatureSet(isChat = false, isAgentControl = isEnabled && isAgentControl)
    }.distinctUntilChanged()

    /** The features right now. */
    suspend fun current(): BridgeFeatureSet = observe().first()
}
