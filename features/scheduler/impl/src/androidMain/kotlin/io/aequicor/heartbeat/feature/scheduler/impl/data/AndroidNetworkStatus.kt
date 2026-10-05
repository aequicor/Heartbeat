package io.aequicor.heartbeat.feature.scheduler.impl.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Connectivity of the default network through [ConnectivityManager] callbacks (`ACCESS_NETWORK_STATE`). */
@ContributesBinding(ProfileScope::class)
@Inject
internal class AndroidNetworkStatus(private val context: Context) : NetworkStatus {
    private val log = Log.tag("AndroidNetworkStatus")
    private val manager: ConnectivityManager? get() = context.getSystemService(ConnectivityManager::class.java)

    override fun observe(): Flow<Boolean> = callbackFlow {
        val connectivity = manager
        if (connectivity == null) {
            log.w { "connectivity service is unavailable" }
            close()
            return@callbackFlow
        }
        trySend(connectivity.isConnected())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(connectivity.isConnected())
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }

    override suspend fun current(): Boolean? = manager?.isConnected()

    private fun ConnectivityManager.isConnected(): Boolean = activeNetwork
        ?.let(::getNetworkCapabilities)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
