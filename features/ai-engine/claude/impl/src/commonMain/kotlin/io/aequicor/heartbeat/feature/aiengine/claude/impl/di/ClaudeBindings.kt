package io.aequicor.heartbeat.feature.aiengine.claude.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeBackend
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineRequirement
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource

/** Registration is lazy: constructing the profile graph never probes the CLI or account. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object ClaudeBindings {
    /** Contributes the adapter without constructing its native runtime. */
    @Provides
    @IntoSet
    public fun registration(backend: Lazy<ClaudeBackend>): EngineRegistration = EngineRegistration(
        descriptor = EngineDescriptor(
            ClaudeEngine.Id,
            "Claude Code",
            EngineFamily.Vendor,
            setOf(EnginePlatform.DesktopMacOs, EnginePlatform.DesktopWindows),
            ClaudeEngine.Enabled,
            requirements = listOf(EngineRequirement("claude-cli", "Native Claude Code CLI with stream-json support")),
            connectionMethods = listOf(
                ConnectionMethod.CliLogin(
                    ConnectionMethodId("cli"),
                    ProviderInfo(ProviderId("anthropic"), "Anthropic"),
                    EndpointOrigin("https://api.anthropic.com"),
                    ClaudeEngine.AuthOwner,
                    ClaudeEngine.AuthLocation,
                ),
            ),
            declaredFeatures = setOf(
                CreatesSessions.id,
                AttachesSessions.id,
                SendsPrompts.id,
                SessionHistory.id,
                ReconcilesSession.id,
            ),
        ),
        authOwner = ClaudeEngine.AuthOwner,
        factory = lazy { backend.value },
        sessionSources = listOf(ClaudeSessionSource(backend)),
    )
}

/** Registers the experimental adapter toggle in the application panel. */
@ContributesTo(AppScope::class)
@BindingContainer
public object ClaudeDefaults {
    /** Disabled by default until the user enables the adapter. */
    @Provides
    @IntoSet
    public fun toggle(): FeatureToggle<*> = ClaudeEngine.Enabled
}

/** Known sessions remain addressable while discovery of external native history is explicitly unsupported. */
private class ClaudeSessionSource(private val backend: Lazy<ClaudeBackend>) : EngineSessionSource {
    override val source = SessionSource(
        ClaudeEngine.SessionSource,
        ClaudeEngine.Id,
        "Claude Code",
    )
    override val discovery: ListsSessions? = null
    override suspend fun get(ref: SessionRef) = backend.value.session(ref)
}
