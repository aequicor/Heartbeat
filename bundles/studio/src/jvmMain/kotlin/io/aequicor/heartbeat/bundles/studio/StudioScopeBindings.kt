package io.aequicor.heartbeat.bundles.studio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.di.AppShutdownParticipant
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledHelperPromptOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledRequestOriginObserver
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider
import kotlinx.coroutines.CoroutineScope

/**
 * Coroutine scopes of the graphs of the bundle. `core:di:impl` provides them in the foundation graph; the bundle graphs
 * do not compile that module, so they derive the scopes from the handles the foundation passes in.
 */
@ContributesTo(AppScope::class)
@BindingContainer
internal object StudioAppScopeBindings {
    /** App-lifetime coroutines. */
    @Provides
    @ForScope(AppScope::class)
    fun appCoroutineScope(
        @ForScope(AppScope::class) scope: ScopeHandle,
    ): CoroutineScope = scope.coroutineScope
}

/** The application sets the bundle exports exist even when no feature of the bundle contributes to one of them. */
@ContributesTo(AppScope::class)
@BindingContainer
internal interface StudioAppSets {
    @Multibinds(allowEmpty = true)
    fun featureToggles(): Set<FeatureToggle<*>>

    @Multibinds(allowEmpty = true)
    fun shutdownParticipants(): Set<AppShutdownParticipant>

    @Multibinds(allowEmpty = true)
    fun profileStorageCleaners(): Set<ProfileStorageCleaner>
}

/** The profile sets the bundle exports exist even when no feature of the bundle contributes to one of them. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal interface StudioProfileSets {
    @Multibinds(allowEmpty = true)
    fun agentTools(): Set<AgentToolContribution>

    @Multibinds(allowEmpty = true)
    fun sessionHooks(): Set<SessionHook>

    @Multibinds(allowEmpty = true)
    fun toolPolicyProviders(): Set<AgentToolPolicyProvider>

    @Multibinds(allowEmpty = true)
    fun contextPolicyProviders(): Set<ContextPolicyProvider>

    @Multibinds(allowEmpty = true)
    fun toolOutputOffloads(): Set<ToolOutputOffload>

    @Multibinds(allowEmpty = true)
    fun nativeActionAnalyzers(): Set<NativeActionAnalysis>

    @Multibinds(allowEmpty = true)
    fun virtualWorkspaceProviders(): Set<VirtualWorkspaceProvider>

    @Multibinds(allowEmpty = true)
    fun scheduledSessionHosts(): Set<ScheduledSessionHost>

    @Multibinds(allowEmpty = true)
    fun scheduledWakeOwners(): Set<ScheduledWakeOwner>

    @Multibinds(allowEmpty = true)
    fun configSections(): Set<ConfigSection>
}

/** The sets of the scheduler and the service-as-ui that the studio reads: the parts of other bundles join them here. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object StudioImportedSets {
    @Provides
    @ElementsIntoSet
    fun helperPromptOwners(imports: StudioProfileImports): Set<ScheduledHelperPromptOwner> =
        imports.helperPromptOwners.toSet()

    @Provides
    @ElementsIntoSet
    fun requestOriginObservers(imports: StudioProfileImports): Set<ScheduledRequestOriginObserver> =
        imports.requestOriginObservers.toSet()

    @Provides
    @ElementsIntoSet
    fun serviceUiProviders(imports: StudioProfileImports): Set<ServiceUiProvider> = imports.serviceUiProviders
}

/** Profile-level counterpart of [StudioAppScopeBindings]. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object StudioProfileScopeBindings {
    /** Profile-lifetime coroutines; cancelled together with the profile. */
    @Provides
    @ForScope(ProfileScope::class)
    fun profileCoroutineScope(
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): CoroutineScope = scope.coroutineScope
}
