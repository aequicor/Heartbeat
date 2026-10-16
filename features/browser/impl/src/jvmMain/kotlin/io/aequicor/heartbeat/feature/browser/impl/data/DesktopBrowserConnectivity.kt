package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserConnectivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.time.Duration.Companion.seconds

/** Desktop link recovery without external probes. Unknown samples never masquerade as an offline transition. */
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopBrowserConnectivity(private val dispatchers: DispatcherProvider) : BrowserConnectivity {
    private val log = Log.tag("BrowserConnectivity")

    override fun restored(): Flow<Unit> = samples().networkRecoveries()

    private fun samples(): Flow<Boolean?> = flow {
        while (true) {
            val isOnline = try {
                NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty().any { network ->
                    network.isUp && !network.isLoopback && !network.isVirtual &&
                        network.inetAddresses.asSequence().any { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                }
            } catch (error: SocketException) {
                log.w(error) { "Browser connectivity could not be inspected" }
                null
            }
            emit(isOnline)
            delay(2.seconds)
        }
    }.flowOn(dispatchers.io)
}

/** Each observer starts without an assumed offline state; reconnect yields exactly one retry signal. */
internal fun Flow<Boolean?>.networkRecoveries(): Flow<Unit> = flow {
    var isPreviouslyOnline: Boolean? = null
    collect { isOnline ->
        if (isOnline != null) {
            if (isPreviouslyOnline == false && isOnline) emit(Unit)
            isPreviouslyOnline = isOnline
        }
    }
}
