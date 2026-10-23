package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeAuthentication
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridge
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDefaults
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeInteractionDemand
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptResourceHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine

/**
 * What the profile graph of the foundation uses of the `engines` bundle; implemented by the host with lazy proxies.
 * The application has no engine to fall back to, so an unavailable bundle surfaces as `BundleUnavailableException`
 * where an engine service is first used.
 */
internal interface FoundationEngineImports {
    /** Sessions and models of the engines. */
    val engineFacade: EngineFacade

    /** Web search. */
    val searchEngine: SearchEngine

    /** Security mode of the agents of the profile. */
    val agentSecuritySettings: AgentSecuritySettings

    /** Whether a native engine waits for a person at the machine. */
    val nativeInteractionDemand: NativeInteractionDemand

    /** Default engine of new routes. */
    val engineDefaults: EngineDefaults

    /** Adapter registrations, including those that other bundles brought to the engines. */
    val registeredEngines: List<EngineRegistration>

    /** Koog connection metadata. */
    val koogConnections: KoogConnections

    /** Account probe of Claude Code. */
    val claudeAuthentication: ClaudeAuthentication

    /** Pi workspace configuration. */
    val piEngine: PiEngine

    /** Sources of engine authentication. */
    val authSources: AuthSources

    /** Checks of the engine authentication sources. */
    val authChecks: AuthChecks

    /** Model choices of the profile's connections. */
    val modelSelections: ModelSelections

    /** Management of the engines. */
    val engineManagement: EngineManagement

    /** Local projects the engines work in. */
    val localWorkspaces: LocalWorkspaces

    /** The tool catalog and dispatcher of the profile's agents. */
    val profileAgentTools: ProfileAgentTools

    /** Bridge that lets native engines call the hosted tools. */
    val agentToolBridge: AgentToolBridge

    /** Isolated one-shot prompts of the facade. */
    val isolatedPrompts: IsolatedPrompts

    /** Durable originals of prompt resources. */
    val promptResourceHistory: PromptResourceHistory
}

/** Bridges the engines proper of the `engines` bundle into the profile graph of the foundation. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object EngineServiceImportBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    fun engineImports(
        host: BundleHost,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): FoundationEngineImports = host.foundationProfileImports(FoundationEngineImports::class, scope)

    @Provides
    fun engineFacade(imports: FoundationEngineImports): EngineFacade = imports.engineFacade

    @Provides
    fun searchEngine(imports: FoundationEngineImports): SearchEngine = imports.searchEngine

    @Provides
    fun agentSecuritySettings(imports: FoundationEngineImports): AgentSecuritySettings = imports.agentSecuritySettings

    @Provides
    fun nativeInteractionDemand(imports: FoundationEngineImports): NativeInteractionDemand =
        imports.nativeInteractionDemand

    @Provides
    fun engineDefaults(imports: FoundationEngineImports): EngineDefaults = imports.engineDefaults

    @Provides
    @ElementsIntoSet
    fun engineRegistrations(imports: FoundationEngineImports): Set<EngineRegistration> =
        imports.registeredEngines.toSet()

    @Provides
    fun koogConnections(imports: FoundationEngineImports): KoogConnections = imports.koogConnections

    @Provides
    fun claudeAuthentication(imports: FoundationEngineImports): ClaudeAuthentication = imports.claudeAuthentication

    @Provides
    fun piEngine(imports: FoundationEngineImports): PiEngine = imports.piEngine
}

/** Bridges accounts, models, projects and tools of the `engines` bundle into the foundation's profile graph. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object EngineAccessImportBindings {
    @Provides
    fun authSources(imports: FoundationEngineImports): AuthSources = imports.authSources

    @Provides
    fun authChecks(imports: FoundationEngineImports): AuthChecks = imports.authChecks

    @Provides
    fun modelSelections(imports: FoundationEngineImports): ModelSelections = imports.modelSelections

    @Provides
    fun engineManagement(imports: FoundationEngineImports): EngineManagement = imports.engineManagement

    @Provides
    fun localWorkspaces(imports: FoundationEngineImports): LocalWorkspaces = imports.localWorkspaces

    @Provides
    fun profileAgentTools(imports: FoundationEngineImports): ProfileAgentTools = imports.profileAgentTools

    @Provides
    fun agentToolBridge(imports: FoundationEngineImports): AgentToolBridge = imports.agentToolBridge

    @Provides
    fun isolatedPrompts(imports: FoundationEngineImports): IsolatedPrompts = imports.isolatedPrompts

    @Provides
    fun promptResourceHistory(imports: FoundationEngineImports): PromptResourceHistory = imports.promptResourceHistory
}
