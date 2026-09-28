package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpCommand
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpStdioTransportFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport

@ContributesBinding(AppScope::class)
@Inject
internal class UnsupportedAcpStdioTransportFactory : AcpStdioTransportFactory {
    override val isSupported: Boolean = false

    override suspend fun open(command: AcpCommand): AcpTransport =
        throw UnsupportedOperationException("Desktop ACP stdio agents are unavailable on this platform")
}
