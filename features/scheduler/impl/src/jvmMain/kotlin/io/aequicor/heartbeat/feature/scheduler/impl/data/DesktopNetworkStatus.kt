package io.aequicor.heartbeat.feature.scheduler.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.time.Duration.Companion.seconds

private val POLL_INTERVAL = 10.seconds

/**
 * The JVM has no connectivity callbacks: the interfaces are polled. Connected means an interface that is up, neither
 * loopback nor virtual, with a routable (not link-local) address.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopNetworkStatus(private val dispatchers: DispatcherProvider) : NetworkStatus {
    private val log = Log.tag("DesktopNetworkStatus")

    override fun observe(): Flow<Boolean> = flow {
        while (true) {
            emit(probe())
            delay(POLL_INTERVAL)
        }
    }.filterNotNull().flowOn(dispatchers.io)

    override suspend fun current(): Boolean? = withContext(dispatchers.io) { probe() }

    @HighFrequency
    private fun probe(): Boolean? = try {
        val isConnected = NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty().any { nic ->
            nic.isUp && !nic.isLoopback && !nic.isVirtual &&
                nic.inetAddresses.asSequence().any { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        }
        log.v { "network connected=$isConnected" }
        isConnected
    } catch (e: SocketException) {
        log.w(e) { "network interfaces are unreadable" }
        null
    }
}
