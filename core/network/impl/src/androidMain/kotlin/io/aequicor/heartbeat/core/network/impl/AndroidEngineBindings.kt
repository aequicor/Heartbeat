package io.aequicor.heartbeat.core.network.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.ktor.client.engine.HttpClientEngine

/** Ktor engine on Android: OkHttp. */
@ContributesTo(AppScope::class)
@BindingContainer
public object AndroidEngineBindings {

    /** Closed with the app scope, after the client. */
    @Provides
    @SingleIn(AppScope::class)
    public fun engine(
        @ForScope(AppScope::class) appScope: ScopeHandle,
    ): HttpClientEngine = createOkHttpEngine().also { engine -> appScope.onClose(engine::close) }
}
