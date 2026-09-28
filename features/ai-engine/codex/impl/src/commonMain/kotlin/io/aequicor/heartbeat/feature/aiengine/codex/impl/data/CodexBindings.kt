package io.aequicor.heartbeat.feature.aiengine.codex.impl.data
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineRequirement
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration

/** Registers metadata without starting a CLI process or inspecting credentials. */
@BindingContainer
@ContributesTo(ProfileScope::class)
public object CodexBindings {
    /** The engine factory is initialized only after facade ownership and toggle checks. */
    @Provides
    @IntoSet
    public fun registration(factory: Lazy<CodexEngineFactory>): EngineRegistration = EngineRegistration(
        descriptor = EngineDescriptor(
            CodexEngine.Id,
            "Codex",
            EngineFamily.Vendor,
            setOf(EnginePlatform.DesktopMacOs, EnginePlatform.DesktopWindows),
            CodexEngine.Enabled,
            requirements = listOf(
                EngineRequirement("codex.app_server", "Установленный Codex CLI с поддержкой app-server"),
            ),
            declaredFeatures = setOf(
                CreatesSessions.id,
                AttachesSessions.id,
                SendsPrompts.id,
                CancelsTurns.id,
                RequestsPermissions.id,
                SessionHistory.id,
            ),
        ),
        authOwner = CodexEngine.AuthOwner,
        factory = lazy { factory.value },
    )
}

/** Adds the Codex switch to the application's toggle control panel. */
@BindingContainer
@ContributesTo(AppScope::class)
public object CodexToggleBindings {
    /** Disabled until the user explicitly enables the integration. */
    @Provides
    @IntoSet
    public fun enabled(): FeatureToggle<*> = CodexEngine.Enabled
}

/** Qualified factory contract prevents collisions with other engine registrations. */
public interface CodexEngineFactory : EngineFactory
