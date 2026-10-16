package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserConnectivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.NetworkInterface
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Desktop link recovery without external probes. Unknown samples never masquerade as an offline transition. */
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopBrowserConnectivity(private val dispatchers: DispatcherProvider) : BrowserConnectivity {
    override fun restored(): Flow<Unit> =
        connectivitySamples(::isNetworkLinkUp).networkRecoveries().flowOn(dispatchers.io)

    private fun isNetworkLinkUp(): Boolean =
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty().any { network ->
            network.isUp && !network.isLoopback && !network.isVirtual &&
                network.inetAddresses.asSequence().any { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        }
}

private val log = Log.tag("BrowserConnectivity")

/**
 * Samples [inspect] every [interval]. A failed inspection is an unknown sample, never an observer failure:
 * connectivity is auxiliary and must not take the browser session down. Only the first failure after a readable
 * sample is logged, so a permanently unreadable host does not flood the log.
 */
internal fun connectivitySamples(inspect: () -> Boolean, interval: Duration = 2.seconds): Flow<Boolean?> = flow {
    var isReadable = true
    while (true) {
        val isOnline = try {
            inspect().also { isReadable = true }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isReadable) log.w(error) { "Browser connectivity could not be inspected" }
            isReadable = false
            null
        }
        emit(isOnline)
        delay(interval)
    }
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
