package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews

/** Platforms without the desktop native renderer cannot claim to have visually checked a document. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnavailableBrowserDocumentPreviews : BrowserDocumentPreviews {
    override suspend fun preview(request: BrowserDocumentPreviewRequest): BrowserDocumentPreviewResult =
        BrowserDocumentPreviewResult.Unavailable
}
