package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DeepSeekNativeCatalog
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain.DeepSeekBackend
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource

/** Lazy registration: graph construction never probes the DSH CLI or its credentials. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object DeepSeekBindings {
    @Provides
    @IntoSet
    internal fun registration(backend: Lazy<DeepSeekBackend>): EngineRegistration = EngineRegistration(
        descriptor = EngineDescriptor(
            DeepSeekEngine.Id,
            "DeepSeek Harness",
            EngineFamily.Vendor,
            setOf(EnginePlatform.DesktopMacOs, EnginePlatform.DesktopWindows, EnginePlatform.DesktopLinux),
            DeepSeekEngine.Enabled,
            requirements = listOf(
                EngineRequirement("deepseek-harness-cli", "User-installed DeepSeek Harness CLI (dsh)"),
            ),
            connectionMethods = listOf(
                ConnectionMethod.CliLogin(
                    ConnectionMethodId("cli"),
                    ProviderInfo(ProviderId("deepseek"), "DeepSeek"),
                    EndpointOrigin("https://api.deepseek.com"),
                    DeepSeekEngine.AuthOwner,
                    DeepSeekEngine.AuthLocation,
                ),
            ),
            isLocalWorkspaceSupported = true,
            nativeTools = DeepSeekNativeCatalog,
            declaredFeatures = setOf(
                AppliesTrustLevels.id,
                CreatesSessions.id,
                SendsPrompts.id,
                SessionHistory.id,
                ReconcilesSession.id,
                CancelsTurns.id,
                RequestsPermissions.id,
            ),
        ),
        authOwner = DeepSeekEngine.AuthOwner,
        factory = lazy { backend.value },
        modelCatalogRevision = 1,
        sessionSources = listOf(DeepSeekSessionSource(backend)),
    )
}

/** Registers the DeepSeek Harness adapter switch in the application panel. */
@ContributesTo(AppScope::class)
@BindingContainer
public object DeepSeekDefaults {
    /** Enables discovery on Desktop; an installed and signed-in CLI is still required. */
    @Provides
    @IntoSet
    public fun toggle(): FeatureToggle<*> = DeepSeekEngine.Enabled
}

private class DeepSeekSessionSource(private val backend: Lazy<DeepSeekBackend>) : EngineSessionSource {
    override val source = SessionSource(DeepSeekEngine.SessionSource, DeepSeekEngine.Id, "DeepSeek Harness")
    override val discovery = null
    override suspend fun get(ref: SessionRef) = backend.value.session(ref)
}
