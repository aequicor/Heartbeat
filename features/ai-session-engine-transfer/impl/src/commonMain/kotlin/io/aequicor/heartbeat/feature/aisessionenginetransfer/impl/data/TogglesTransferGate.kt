package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionEngineTransfer
import io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.domain.TransferGate

/** Transfers need both the engine integration and the transfer feature itself. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class TogglesTransferGate(private val toggles: FeatureToggles) : TransferGate {
    private val log = Log.tag("TogglesTransferGate")

    override suspend fun isEnabled(): Boolean {
        val isEnginesEnabled = toggles.get(AiEngines)
        val isTransferEnabled = toggles.get(SessionEngineTransfer)
        log.d { "transfer gate: engines=$isEnginesEnabled, transfer=$isTransferEnabled" }
        return isEnginesEnabled && isTransferEnabled
    }
}
