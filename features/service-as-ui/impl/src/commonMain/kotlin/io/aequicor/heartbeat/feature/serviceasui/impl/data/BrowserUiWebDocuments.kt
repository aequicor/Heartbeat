package io.aequicor.heartbeat.feature.serviceasui.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.api.BrowserDocument
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentHandle
import io.aequicor.heartbeat.feature.browser.api.BrowserDocuments
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiWeb
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import kotlinx.coroutines.flow.Flow

/** Web documents of surfaces through the profile's browser documents, while `service_ui.web` is on. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class BrowserUiWebDocuments(toggles: FeatureToggles, private val documents: BrowserDocuments) :
    UiWebDocuments {
    override val availability: Flow<Boolean> = toggles.observe(ServiceUiWeb)

    override fun open(document: BrowserDocument): BrowserDocumentHandle? = documents.open(document)
}
