package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews
import io.aequicor.heartbeat.feature.browser.api.BrowserDocuments

/**
 * What the profile graph of the foundation takes from the profile graphs of the bundles; implemented by the host
 * with lazy proxies. Entries of sets are merged with the contributions the foundation graph compiles itself.
 */
internal interface FoundationProfileBundleImports {
    /** Documents of the `web` bundle's browser. */
    val documents: BrowserDocuments

    /** Visual checks of documents in the `web` bundle's browser. */
    val documentPreviews: BrowserDocumentPreviews

    /** Navigation entries of the `web` bundle. */
    val profileRoutes: Set<RouteEntry<*>>

    /** Services the `web` bundle starts with the profile. */
    val profileStartups: Set<ProfileStartup>

    /** Agent tools of the `web` bundle. */
    val agentTools: Set<AgentToolContribution>
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
    @ElementsIntoSet
    fun bundleAgentTools(imports: FoundationProfileBundleImports): Set<AgentToolContribution> =
        imports.orElse(emptySet()) { agentTools }

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
