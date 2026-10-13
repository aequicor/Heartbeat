package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aistudio.api.StudioChats
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeAgentControl
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEnabled
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeFeatureSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** Bridge features the app offers to extensions, from the rollout toggles, the profile chats and studio views. */
internal class ToggleBridgeFeatures(
    private val toggles: FeatureToggles,
    private val chats: StudioChats,
    private val studio: StudioRemoteScreens,
) {
    /** Current features; extensions receive changes as `app.features`. */
    fun observe(): Flow<BridgeFeatureSet> = combine(
        toggles.observe(ChromiumBridgeEnabled),
        toggles.observe(ChromiumBridgeAgentControl),
        chats.availability,
        studio.availability,
    ) { isEnabled, isAgentControl, isChat, isStudio ->
        BridgeFeatureSet(
            isChat = isEnabled && isChat,
            isAgentControl = isEnabled && isAgentControl,
            isStudio = isEnabled && isStudio,
        )
    }.distinctUntilChanged()

    /** The features right now. */
    suspend fun current(): BridgeFeatureSet = observe().first()
}
