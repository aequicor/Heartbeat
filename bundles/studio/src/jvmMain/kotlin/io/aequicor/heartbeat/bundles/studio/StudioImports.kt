package io.aequicor.heartbeat.bundles.studio

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.BundleUnavailableException
import io.aequicor.heartbeat.core.bundles.ConfigSectionPart
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews
import io.aequicor.heartbeat.feature.browser.api.BrowserDocuments
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider

/**
 * What the profile graph of `studio` takes from other bundles; implemented by the host with lazy proxies. The set of
 * service-as-ui providers is owned by `studio` (its feature reads it), so the bundle never exports it.
 */
public interface StudioProfileImports {
    /** Documents that service-as-ui surfaces show in the embedded browser (`web`). */
    public val documents: BrowserDocuments

    /** Rendering of document previews for the interface tools (`web`). */
    public val documentPreviews: BrowserDocumentPreviews

    /** Providers of interface methods and owners that the harnesses of `agent-runtime` export. */
    public val serviceUiProviders: Set<ServiceUiProvider>
}

/** Documents while the `web` bundle is not running: nothing is shown, as if the document limit were reached. */
private object UnavailableDocuments : BrowserDocuments {
    override fun open(document: BrowserDocument): BrowserDocumentHandle? = null
}

/** Previews while the `web` bundle is not running: no renderer. */
private object UnavailablePreviews : BrowserDocumentPreviews {
    override suspend fun preview(request: BrowserDocumentPreviewRequest): BrowserDocumentPreviewResult =
        BrowserDocumentPreviewResult.Unavailable
}

/** Bridges what other bundles export into the profile graph of the `studio` bundle. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object StudioImportBindings {
    private val log = Log.tag("StudioImports")

    @Provides
    fun documents(imports: StudioProfileImports): BrowserDocuments = try {
        imports.documents
    } catch (e: BundleUnavailableException) {
        log.w(e) { "The web bundle is unavailable; interface surfaces show no web documents" }
        UnavailableDocuments
    }

    @Provides
    fun documentPreviews(imports: StudioProfileImports): BrowserDocumentPreviews = try {
        imports.documentPreviews
    } catch (e: BundleUnavailableException) {
        log.w(e) { "The web bundle is unavailable; interface previews have no renderer" }
        UnavailablePreviews
    }

    @Provides
    @ElementsIntoSet
    fun serviceUiProviders(imports: StudioProfileImports): Set<ServiceUiProvider> = imports.serviceUiProviders
}

/** Hands the project configuration sections of the bundle's features to `agent-runtime`, which owns the tree. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object StudioConfigSectionBindings {
    @Provides
    fun configSectionPart(sections: Provider<Set<ConfigSection>>): ConfigSectionPart = object : ConfigSectionPart {
        override val configSections: Set<ConfigSection> get() = sections()
    }
}
