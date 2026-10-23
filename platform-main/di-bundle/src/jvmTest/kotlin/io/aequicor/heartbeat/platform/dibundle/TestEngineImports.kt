package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpStdioTransportFactory
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.AuthCredentials
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration

/** Engine services at the application level that the integration tests read from the `engines` bundle. */
interface TestEngineAppImports {
    val acpClients: AcpClientFactory
    val acpStdio: AcpStdioTransportFactory
}

/** Engine services of a profile that the integration tests read from the `engines` bundle. */
interface TestEngineProfileImports {
    val authCredentials: AuthCredentials
    val engineConfiguration: EngineConfiguration
    val agentToolContributions: List<AgentToolContribution>
}

/** Makes the application graph of the tests see the engine services of the bundle, like the foundation does. */
@ContributesTo(AppScope::class)
@BindingContainer
object TestEngineAppBridge {
    @Provides
    @SingleIn(AppScope::class)
    fun imports(host: BundleHost): TestEngineAppImports = host.foundationImports(TestEngineAppImports::class)

    @Provides
    fun acpClients(imports: TestEngineAppImports): AcpClientFactory = imports.acpClients

    @Provides
    fun acpStdio(imports: TestEngineAppImports): AcpStdioTransportFactory = imports.acpStdio
}

/** Profile-level counterpart of [TestEngineAppBridge]. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object TestEngineProfileBridge {
    @Provides
    @SingleIn(ProfileScope::class)
    fun imports(
        host: BundleHost,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): TestEngineProfileImports = host.foundationProfileImports(TestEngineProfileImports::class, scope)

    @Provides
    fun authCredentials(imports: TestEngineProfileImports): AuthCredentials = imports.authCredentials

    @Provides
    fun engineConfiguration(imports: TestEngineProfileImports): EngineConfiguration = imports.engineConfiguration

    /** The catalog of every bundle; the foundation's own `Set<AgentToolContribution>` has only its part. */
    @Provides
    fun agentToolContributions(imports: TestEngineProfileImports): List<AgentToolContribution> =
        imports.agentToolContributions
}
