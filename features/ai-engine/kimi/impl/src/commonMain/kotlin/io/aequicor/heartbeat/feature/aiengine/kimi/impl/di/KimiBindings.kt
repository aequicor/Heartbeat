package io.aequicor.heartbeat.feature.aiengine.kimi.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineRequirement
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.domain.KimiBackend

/** Lazy registration: graph construction never probes or starts Kimi CLI. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object KimiBindings {
    @Provides
    @IntoSet
    internal fun registration(backend: Lazy<KimiBackend>): EngineRegistration = EngineRegistration(
        descriptor = EngineDescriptor(
            KimiEngine.Id,
            "Kimi CLI",
            EngineFamily.Vendor,
            setOf(EnginePlatform.DesktopMacOs, EnginePlatform.DesktopWindows, EnginePlatform.DesktopLinux),
            KimiEngine.Enabled,
            requirements = listOf(EngineRequirement("kimi-cli", "Kimi CLI CLI with ACP support", "0.9.0")),
            connectionMethods = listOf(
                ConnectionMethod.CliLogin(
                    ConnectionMethodId("cli"),
                    ProviderInfo(ProviderId("kimi"), "Kimi"),
                    EndpointOrigin("https://api.kimi.com"),
                    KimiEngine.AuthOwner,
                    KimiEngine.AuthLocation,
                ),
            ),
            isLocalWorkspaceSupported = true,
            declaredFeatures = setOf(
                CreatesSessions.id,
                AttachesSessions.id,
                SendsPrompts.id,
                SessionHistory.id,
                ReconcilesSession.id,
                CancelsTurns.id,
                RequestsPermissions.id,
            ),
        ),
        authOwner = KimiEngine.AuthOwner,
        factory = lazy { backend.value },
        sessionSources = listOf(KimiSessionSource(backend)),
    )
}

/** Registers the Kimi adapter switch in the application panel. */
@ContributesTo(AppScope::class)
@BindingContainer
public object KimiDefaults {
    /** Enables discovery on Desktop; an installed and signed-in CLI is still required. */
    @Provides
    @IntoSet
    public fun toggle(): FeatureToggle<*> = KimiEngine.Enabled
}

private class KimiSessionSource(private val backend: Lazy<KimiBackend>) : EngineSessionSource {
    override val source = SessionSource(KimiEngine.SessionSource, KimiEngine.Id, "Kimi CLI")
    override val discovery = null
    override suspend fun get(ref: SessionRef) = backend.value.session(ref)
}
