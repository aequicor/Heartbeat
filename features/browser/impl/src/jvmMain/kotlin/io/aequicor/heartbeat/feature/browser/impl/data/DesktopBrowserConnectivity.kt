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
    val readability = ConnectivityReadability()
    while (true) {
        val isOnline = try {
            inspect().also { readability.readable() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (readability.failed()) {
                log.w(error) { "Browser connectivity could not be inspected" }
            } else {
                log.v { "Browser connectivity is still unreadable" }
            }
            null
        }
        emit(isOnline)
        delay(interval)
    }
}

/** Tracks unreadable periods so a failure is warned once per period; repeats stay at trace level. */
private class ConnectivityReadability {
    private var isReadable = true

    fun readable() {
        if (!isReadable) log.d { "Browser connectivity is readable again" }
        isReadable = true
    }

    /** Whether this failure starts an unreadable period. */
    fun failed(): Boolean = isReadable.also { isReadable = false }
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
