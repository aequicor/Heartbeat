package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.bundles.BundleUnavailableException
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.AuthCredentials
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeAuthentication
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDefaults
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeInteractionDemand
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspaceProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngine
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews
import io.aequicor.heartbeat.feature.browser.api.BrowserDocuments
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine

/**
 * What the profile graph of the foundation takes from the profile graphs of the bundles; implemented by the host
 * with lazy proxies. Entries of sets are merged with the contributions the foundation graph compiles itself.
 */
internal interface FoundationProfileBundleImports {
    /** Documents of the `web` bundle's browser. */
    val documents: BrowserDocuments

    /** Visual checks of documents in the `web` bundle's browser. */
    val documentPreviews: BrowserDocumentPreviews

    /** Navigation entries of the `web`, `engines` and `agent-runtime` bundles. */
    val profileRoutes: Set<RouteEntry<*>>

    /** Services the bundles start with the profile. */
    val profileStartups: Set<ProfileStartup>

    /** Sessions and models of the `engines` bundle. */
    val engineFacade: EngineFacade

    /** Web search of the `engines` bundle. */
    val searchEngine: SearchEngine

    /** Authorization checks of the `engines` bundle. */
    val authChecks: AuthChecks

    /** Credentials of the `engines` bundle's sources. */
    val authCredentials: AuthCredentials

    /** Engine configuration of the `engines` bundle. */
    val engineConfiguration: EngineConfiguration

    /** Engine management of the `engines` bundle. */
    val engineManagement: EngineManagement

    /** Agent tools of the `engines` bundle. */
    val profileAgentTools: ProfileAgentTools

    /** Agent security switches of the `engines` bundle. */
    val agentSecuritySettings: AgentSecuritySettings

    /** Authorization sources of the `engines` bundle. */
    val authSources: AuthSources

    /** Isolated prompts of the `engines` bundle. */
    val isolatedPrompts: IsolatedPrompts

    /** Local projects of the `engines` bundle. */
    val localWorkspaces: LocalWorkspaces

    /** Model choices of the `engines` bundle. */
    val modelSelections: ModelSelections

    /** Native interaction demand of the `engines` bundle's sessions. */
    val nativeInteractionDemand: NativeInteractionDemand

    /** Claude Code CLI login of the `engines` bundle. */
    val claudeAuthentication: ClaudeAuthentication

    /** Pi adapter of the `engines` bundle. */
    val piEngine: PiEngine

    /** Default engine choices of the `engines` bundle. */
    val engineDefaults: EngineDefaults

    /** Engine registrations of the `engines` bundle. */
    val engineRegistrations: Set<EngineRegistration>

    /** Koog connections of the `engines` bundle. */
    val koogConnections: KoogConnections
}

/** Documents while the `web` bundle is not running: nothing is shown, as if the document limit were reached. */
internal object UnavailableBrowserDocuments : BrowserDocuments {
    override fun open(document: BrowserDocument): BrowserDocumentHandle? = null
}

/** Previews while the `web` bundle is not running: no renderer. */
internal object UnavailableBrowserDocumentPreviews : BrowserDocumentPreviews {
    override suspend fun preview(request: BrowserDocumentPreviewRequest): BrowserDocumentPreviewResult =
        BrowserDocumentPreviewResult.Unavailable
}

/**
 * Every engine registration of the profile: the adapters of the `engines` bundle and the ones the foundation
 * contributes. A holder rather than a `Set`, because the foundation's own `Set<EngineRegistration>` holds only its part
 * (`engines` merges it into its set and exports the result; merging that back would read the set from itself).
 */
public interface RegisteredEngines {
    /** Registrations of the `engines` bundle and of the foundation. */
    public val all: Set<EngineRegistration>
}

/**
 * Sets that the `engines` bundle owns: its facade assembles them from its own contributions and from the parts the
 * foundation (through `FoundationProfileBindings`) and other bundles export. The foundation keeps the declarations so
 * that its own parts resolve while no feature contributes, and never merges the bundle's part back.
 */
@ContributesTo(ProfileScope::class)
internal interface EngineOwnedSets {
    @Multibinds(allowEmpty = true)
    fun engineRegistrations(): Set<EngineRegistration>

    @Multibinds(allowEmpty = true)
    fun agentTools(): Set<AgentToolContribution>

    @Multibinds(allowEmpty = true)
    fun nativeActionAnalyzers(): Set<NativeActionAnalysis>

    @Multibinds(allowEmpty = true)
    fun toolOutputOffloads(): Set<ToolOutputOffload>

    @Multibinds(allowEmpty = true)
    fun sessionHooks(): Set<SessionHook>

    @Multibinds(allowEmpty = true)
    fun toolPolicyProviders(): Set<AgentToolPolicyProvider>

    @Multibinds(allowEmpty = true)
    fun contextPolicyProviders(): Set<ContextPolicyProvider>

    @Multibinds(allowEmpty = true)
    fun virtualWorkspaceProviders(): Set<VirtualWorkspaceProvider>
}

/**
 * Sets that the `agent-runtime` bundle owns: its project configuration assembles them from its own contributions and
 * from the part the foundation (through `FoundationProfileBindings`) hands over. The foundation keeps the declarations
 * so that its own parts resolve while no feature contributes, and never merges the bundle's part back.
 */
@ContributesTo(ProfileScope::class)
internal interface AgentRuntimeOwnedSets {
    @Multibinds(allowEmpty = true)
    fun configSections(): Set<ConfigSection>
}

/** Bridges the profile exports of the bundles into the profile graph of the foundation. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object ProfileBundleImportBindings {
    private val log = Log.tag("BundleImports")

    @Provides
    @SingleIn(ProfileScope::class)
    fun profileImports(
        host: BundleHost,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): FoundationProfileBundleImports = host.foundationProfileImports(FoundationProfileBundleImports::class, scope)

    @Provides
    fun documents(imports: FoundationProfileBundleImports): BrowserDocuments =
        imports.orElse(UnavailableBrowserDocuments) { documents }

    @Provides
    fun documentPreviews(imports: FoundationProfileBundleImports): BrowserDocumentPreviews =
        imports.orElse(UnavailableBrowserDocumentPreviews) { documentPreviews }

    @Provides
    @ElementsIntoSet
    @ForScope(ProfileScope::class)
    fun bundleRoutes(imports: FoundationProfileBundleImports): Set<RouteEntry<*>> =
        imports.orElse(emptySet()) { profileRoutes }

    @Provides
    @ElementsIntoSet
    fun bundleStartups(imports: FoundationProfileBundleImports): Set<ProfileStartup> =
        imports.orElse(emptySet()) { profileStartups }

    @Provides
    fun registeredEngines(imports: FoundationProfileBundleImports): RegisteredEngines = object : RegisteredEngines {
        override val all: Set<EngineRegistration> get() = imports.orElse(emptySet()) { engineRegistrations }
    }

    /** A bundle that is not running leaves its part of the profile out, like a platform without it. */
    private inline fun <T> FoundationProfileBundleImports.orElse(
        fallback: T,
        read: FoundationProfileBundleImports.() -> T,
    ): T = try {
        read()
    } catch (e: BundleUnavailableException) {
        log.w(e) { "A bundle of the profile is unavailable; its part stays out of the profile" }
        fallback
    }
}

/**
 * Services of the `engines` bundle the foundation features use. They have no stand-in: without the bundle the profile
 * has no AI, and a read reports why.
 */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object EngineServiceImportBindings {
    @Provides
    fun engineFacade(imports: FoundationProfileBundleImports): EngineFacade = imports.engineFacade

    @Provides
    fun searchEngine(imports: FoundationProfileBundleImports): SearchEngine = imports.searchEngine

    @Provides
    fun profileAgentTools(imports: FoundationProfileBundleImports): ProfileAgentTools = imports.profileAgentTools

    @Provides
    fun agentSecuritySettings(imports: FoundationProfileBundleImports): AgentSecuritySettings =
        imports.agentSecuritySettings

    @Provides
    fun authSources(imports: FoundationProfileBundleImports): AuthSources = imports.authSources

    @Provides
    fun isolatedPrompts(imports: FoundationProfileBundleImports): IsolatedPrompts = imports.isolatedPrompts

    @Provides
    fun localWorkspaces(imports: FoundationProfileBundleImports): LocalWorkspaces = imports.localWorkspaces

    @Provides
    fun modelSelections(imports: FoundationProfileBundleImports): ModelSelections = imports.modelSelections

    @Provides
    fun nativeInteractionDemand(imports: FoundationProfileBundleImports): NativeInteractionDemand =
        imports.nativeInteractionDemand
}

/** Engine adapters and their control surface, read from the `engines` bundle like [EngineServiceImportBindings]. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object EngineAdapterImportBindings {
    @Provides
    fun authChecks(imports: FoundationProfileBundleImports): AuthChecks = imports.authChecks

    @Provides
    fun authCredentials(imports: FoundationProfileBundleImports): AuthCredentials = imports.authCredentials

    @Provides
    fun engineConfiguration(imports: FoundationProfileBundleImports): EngineConfiguration = imports.engineConfiguration

    @Provides
    fun engineManagement(imports: FoundationProfileBundleImports): EngineManagement = imports.engineManagement

    @Provides
    fun claudeAuthentication(imports: FoundationProfileBundleImports): ClaudeAuthentication =
        imports.claudeAuthentication

    @Provides
    fun piEngine(imports: FoundationProfileBundleImports): PiEngine = imports.piEngine

    @Provides
    fun engineDefaults(imports: FoundationProfileBundleImports): EngineDefaults = imports.engineDefaults

    @Provides
    fun koogConnections(imports: FoundationProfileBundleImports): KoogConnections = imports.koogConnections
}
