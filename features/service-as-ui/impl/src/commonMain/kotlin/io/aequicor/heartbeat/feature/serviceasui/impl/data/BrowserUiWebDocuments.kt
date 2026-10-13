package io.aequicor.heartbeat.feature.serviceasui.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocuments
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWeb
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Web documents of surfaces through the profile's browser documents, while `service_ui.web` is on and only on
 * desktop: elsewhere the browser cannot isolate them, so `web` nodes keep their note and open nothing.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class BrowserUiWebDocuments(
    toggles: FeatureToggles,
    platform: PlatformInfo,
    private val documents: BrowserDocuments,
) : UiWebDocuments {
    private val isDesktop = platform.host in setOf(HostPlatform.MacOs, HostPlatform.Windows, HostPlatform.Linux)

    override val availability: Flow<Boolean> = toggles.observe(ServiceUiWeb).map { it && isDesktop }

    override fun open(document: BrowserDocument): BrowserDocumentHandle? = documents.open(document)
}
