package io.aequicor.heartbeat.core.network.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.NetworkConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

// Binding containers are public: the graph that includes them is generated in :platform-main:di-bundle, and
// `generateContributionProviders` does not cover containers. core:network:impl is visible only to the bundle.
// The engine is contributed per platform (`<Platform>EngineBindings` in androidMain / jvmMain / iosMain).

/** The application [HttpClient]. */
@ContributesTo(AppScope::class)
@BindingContainer
public object NetworkBindings {

    /**
     * One client (and one connection pool) per process, closed with the app scope.
     * [config] falls back to the defaults unless the graph provides a [NetworkConfig].
     */
    @Provides
    @SingleIn(AppScope::class)
    public fun httpClient(
        engine: HttpClientEngine,
        @ForScope(AppScope::class) appScope: ScopeHandle,
        config: NetworkConfig = NetworkConfig(),
    ): HttpClient {
        val client = createHttpClient(engine, config)
        Log.tag(NET_LOG_TAG).i { "http client created: $config" }
        appScope.onClose(client::close)
        return client
    }
}
