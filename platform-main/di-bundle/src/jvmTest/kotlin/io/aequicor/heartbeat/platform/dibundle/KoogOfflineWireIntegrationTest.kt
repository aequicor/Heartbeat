package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.network.OfflineHttpClients
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Exercises the internal probe with the production network implementation across bundle boundaries. */
class KoogOfflineWireIntegrationTest {
    @Test
    fun `native probes match the real socket free transport`() = runBlocking {
        val dispatchers = object : DispatcherProvider {
            override val main = Dispatchers.Default
            override val default = Dispatchers.Default
            override val io = Dispatchers.IO
        }
        val platform = object : PlatformInfo {
            override val host = HostPlatform.MacOs
        }
        val offline = Class.forName("io.aequicor.heartbeat.core.network.impl.SocketFreeHttpClients")
            .getDeclaredConstructor(DispatcherProvider::class.java)
            .newInstance(dispatchers) as OfflineHttpClients
        val type = Class.forName("io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.KoogNativeProbe")
        val probe = type.getDeclaredConstructor(DispatcherProvider::class.java).newInstance(dispatchers)
        for (provider in KoogProvider.entries) {
            for (probeName in listOf("runProviderWire", "runIncompleteWire")) {
                val method = type.getDeclaredMethod(
                    probeName,
                    TimeMark::class.java,
                    OfflineHttpClients::class.java,
                    PlatformInfo::class.java,
                    KoogProvider::class.java,
                    Continuation::class.java,
                )
                val confirmed = suspendCoroutineUninterceptedOrReturn<Boolean> { continuation ->
                    method.invoke(
                        probe,
                        TimeSource.Monotonic.markNow() + 10.seconds,
                        offline,
                        platform,
                        provider,
                        continuation,
                    )
                }
                assertTrue(confirmed, "${provider.name}: $probeName")
            }
        }
    }
}
