package io.aequicor.heartbeat.feature.chromiumbridge.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExternalBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.DesktopBridgeEnvironment
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.DesktopExtensionFolder
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.ToggleBridgeFeatures
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.agent.ChromiumExternalBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat.BridgeWatches
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat.SidePanelStudioHandlers
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts.DesktopNativeHostRegistry
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts.JnaWindowsRegistry
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeConnections
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeDispatcher
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeRequestHandler
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.DesktopBridgeServer
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.HelloHandler
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.BridgeServer
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.ExtensionFolder
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.ExtensionIdentity
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.NativeHostRegistry
import kotlinx.serialization.json.Json

/** Desktop server, host registry and request handlers of the bridge. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object JvmChromiumBridgeBindings {
    /** Bridge files, socket and browser locations. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun environment(
        @ForScope(AppScope::class) stores: Lazy<DataStores>,
        platform: PlatformInfo,
    ): DesktopBridgeEnvironment = DesktopBridgeEnvironment(stores, platform)

    /** Routes extension requests to the feature's handlers. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun dispatcher(handlers: Set<BridgeRequestHandler>): BridgeDispatcher = BridgeDispatcher(handlers)

    /** Live extension connections of the profile. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun connections(dispatcher: BridgeDispatcher): BridgeConnections =
        BridgeConnections(dispatcher, ExtensionIdentity::isAllowedOrigin)

    /** The handshake. */
    @Provides
    @IntoSet
    internal fun hello(features: ToggleBridgeFeatures): BridgeRequestHandler =
        HelloHandler(System.getProperty(APP_VERSION) ?: DEVELOPMENT_VERSION) { features.current() }

    /** Outside chat turns need the desktop browser route; mobile has no native messaging host. */
    @Provides
    @IntoSet
    internal fun studio(
        studio: StudioRemoteScreens,
        watches: BridgeWatches,
        browser: Lazy<ExternalBrowser>,
    ): BridgeRequestHandler = SidePanelStudioHandlers(studio, watches) { session ->
        object : StudioChatEnvironment {
            override val preselected = mapOf("external_browser" to setOf(session.id.toString()))
            override fun bind(sessionRef: SessionRef): AutoCloseable = browser.value.bindSession(
                Json.encodeToString(SessionRef.serializer(), sessionRef),
                session.id.toString(),
            )
        }
    }

    /** The socket. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun server(
        environment: DesktopBridgeEnvironment,
        connections: BridgeConnections,
        features: ToggleBridgeFeatures,
        dispatchers: DispatcherProvider,
    ): BridgeServer = DesktopBridgeServer(environment::socket, connections, features, dispatchers.io)

    /** The external browser agent tools drive, over the newest agent-capable connection. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun externalBrowser(
        connections: BridgeConnections,
        @ForScope(ProfileScope::class) profile: ScopeHandle,
    ): ExternalBrowser = ChromiumExternalBrowser(connections.sessions, profile.coroutineScope)

    /** The extension folder to load unpacked. */
    @Provides
    internal fun extension(dispatchers: DispatcherProvider): ExtensionFolder = DesktopExtensionFolder(dispatchers.io)

    /** Browser registrations. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun hosts(environment: DesktopBridgeEnvironment, dispatchers: DispatcherProvider): NativeHostRegistry =
        DesktopNativeHostRegistry(environment::host, JnaWindowsRegistry(), dispatchers.io)

    private const val APP_VERSION = "heartbeat.app.version"
    private const val DEVELOPMENT_VERSION = "development"
}
