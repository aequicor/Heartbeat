package io.aequicor.heartbeat.feature.chromiumbridge.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.chromiumbridge.api.BridgeServerStatus
import io.aequicor.heartbeat.feature.chromiumbridge.api.BrowserHost
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ConnectedBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistration
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistrationFailure
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.BridgeServer
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.ExtensionFolder
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.HostRegistrationException
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.NativeHostRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Mobile platforms have no native messaging: the machine stays disabled, these only satisfy the graph. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object UnsupportedChromiumBridgeBindings {
    /** No socket on mobile. */
    @Provides
    internal fun server(): BridgeServer = object : BridgeServer {
        override suspend fun serve(onStatus: suspend (BridgeServerStatus) -> Unit) = onStatus(BridgeServerStatus.Failed)

        override fun clients(): Flow<List<ConnectedBrowser>> = flowOf(emptyList())

        override fun controlledTabs(): Flow<Int> = flowOf(0)

        override suspend fun revokeTabs(): Int = 0
    }

    /** No browsers to register on mobile. */
    @Provides
    internal fun hosts(): NativeHostRegistry = object : NativeHostRegistry {
        override suspend fun scan(): List<BrowserHost> = emptyList()

        override suspend fun register(browser: ChromiumBrowser): BrowserHost =
            throw HostRegistrationException(HostRegistrationFailure.UnsupportedPlatform)

        override suspend fun unregister(browser: ChromiumBrowser): BrowserHost =
            BrowserHost(browser, isDetected = false, HostRegistration.NotRegistered)
    }

    /** No extension folder on mobile. */
    @Provides
    internal fun extension(): ExtensionFolder = object : ExtensionFolder {
        override suspend fun locate(): String? = null

        override suspend fun reveal(folder: String) = Unit
    }
}
