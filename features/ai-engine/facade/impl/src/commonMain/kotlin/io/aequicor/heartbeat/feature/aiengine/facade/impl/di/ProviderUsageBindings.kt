package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.FeatureToggleUsageGate
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EnabledEngines
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ProviderUsageCatalogService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RouteResolver
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RuntimePool

/** Profile-memory provider telemetry backed by the existing checked routes and native runtime pool. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object ProviderUsageBindings {
    /** A single catalog and native observation per profile/runtime identity. */
    @Provides
    @SingleIn(ProfileScope::class)
    @Suppress("LongParameterList") // A DI provider mirrors the independent gates required by the catalog.
    public fun providerUsage(
        routes: RouteResolver,
        pool: RuntimePool,
        enabled: EnabledEngines,
        sources: AuthSources,
        toggles: FeatureToggles,
        context: FacadeContext,
    ): ProviderUsageCatalog = ProviderUsageCatalogService(
        routes,
        pool,
        enabled,
        sources,
        FeatureToggleUsageGate(toggles),
        context,
    )
}
