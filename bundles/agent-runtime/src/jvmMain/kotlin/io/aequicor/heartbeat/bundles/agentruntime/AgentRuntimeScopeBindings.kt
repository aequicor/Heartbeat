package io.aequicor.heartbeat.bundles.agentruntime

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.FoundationProfileRuntimeBindings
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
internal object AgentRuntimeAppScopeBindings {
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
internal interface AgentRuntimeAppSets {
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
internal interface AgentRuntimeProfileSets {
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
    fun scheduledHelperPromptOwners(): Set<ScheduledHelperPromptOwner>

    @Multibinds(allowEmpty = true)
    fun scheduledRequestOriginObservers(): Set<ScheduledRequestOriginObserver>

    @Multibinds(allowEmpty = true)
    fun serviceUiProviders(): Set<ServiceUiProvider>
}

/** The parts of the scheduler's and the project configuration's sets that the foundation's features contribute. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object AgentRuntimeFoundationSets {
    @Provides
    @ElementsIntoSet
    fun scheduledSessionHosts(foundation: FoundationProfileRuntimeBindings): Set<ScheduledSessionHost> =
        foundation.scheduledSessionHosts

    @Provides
    @ElementsIntoSet
    fun scheduledWakeOwners(foundation: FoundationProfileRuntimeBindings): Set<ScheduledWakeOwner> =
        foundation.scheduledWakeOwners

    @Provides
    @ElementsIntoSet
    fun configSections(foundation: FoundationProfileRuntimeBindings): Set<ConfigSection> = foundation.configSections

    @Provides
    @ElementsIntoSet
    fun importedSessionHosts(imports: AgentRuntimeProfileImports): Set<ScheduledSessionHost> =
        imports.scheduledSessionHosts

    @Provides
    @ElementsIntoSet
    fun importedWakeOwners(imports: AgentRuntimeProfileImports): Set<ScheduledWakeOwner> = imports.scheduledWakeOwners

    @Provides
    @ElementsIntoSet
    fun importedConfigSections(imports: AgentRuntimeProfileImports): Set<ConfigSection> = imports.configSections

    @Provides
    @ElementsIntoSet
    fun helperPromptOwners(imports: AgentRuntimeProfileImports): Set<ScheduledHelperPromptOwner> =
        imports.scheduledHelperPromptOwners

    @Provides
    @ElementsIntoSet
    fun requestOriginObservers(imports: AgentRuntimeProfileImports): Set<ScheduledRequestOriginObserver> =
        imports.scheduledRequestOriginObservers

    @Provides
    fun helperPromptOwnerList(owners: Set<ScheduledHelperPromptOwner>): List<ScheduledHelperPromptOwner> =
        owners.toList()

    @Provides
    fun requestOriginObserverList(
        observers: Set<ScheduledRequestOriginObserver>,
    ): List<ScheduledRequestOriginObserver> = observers.toList()

    @Provides
    fun configSectionList(sections: Set<ConfigSection>): List<ConfigSection> = sections.toList()
}

/** Profile-level counterpart of [AgentRuntimeAppScopeBindings]. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object AgentRuntimeProfileScopeBindings {
    /** Profile-lifetime coroutines; cancelled together with the profile. */
    @Provides
    @ForScope(ProfileScope::class)
    fun profileCoroutineScope(
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): CoroutineScope = scope.coroutineScope
}
