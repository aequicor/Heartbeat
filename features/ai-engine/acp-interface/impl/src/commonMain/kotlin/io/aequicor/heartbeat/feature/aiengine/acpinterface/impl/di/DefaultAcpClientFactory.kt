package io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import io.aequicor.heartbeat.feature.aiengine.acpinterface.impl.data.DefaultAcpConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

@ContributesBinding(AppScope::class)
@Inject
internal class DefaultAcpClientFactory(private val dispatchers: DispatcherProvider) : AcpClientFactory {
    override fun connect(transport: AcpTransport, scope: CoroutineScope, handler: AcpClientHandler): AcpConnection {
        val parent = requireNotNull(scope.coroutineContext[Job]) { "ACP requires an owned scope with a Job" }
        val lifetime = SupervisorJob(parent)
        val connectionScope = CoroutineScope(scope.coroutineContext + lifetime + dispatchers.default)
        return DefaultAcpConnection(transport, connectionScope, handler)
    }
}
