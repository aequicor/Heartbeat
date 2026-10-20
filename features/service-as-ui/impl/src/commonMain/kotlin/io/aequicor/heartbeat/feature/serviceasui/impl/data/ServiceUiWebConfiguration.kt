package io.aequicor.heartbeat.feature.serviceasui.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWeb
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Rendering is an executable action authorized by the common tool gate, including external assets. */
internal fun FeatureToggles.uiWebConfiguration(isDesktop: Boolean): Flow<UiWebConfiguration> = combine(
    observe(ServiceUiEnabled),
    observe(ServiceUiWeb),
) { enabled, web ->
    UiWebConfiguration(isDesktop && enabled && web, BrowserDocumentResourcePolicy.ExternalAssets)
}.distinctUntilChanged()

/** One renderer resource contract; authorization comes from the profile permission mode. */
internal fun uiWebResourcePolicy(): BrowserDocumentResourcePolicy = BrowserDocumentResourcePolicy.ExternalAssets

/** Guidance describes execution effects without introducing another permission setting. */
internal suspend fun FeatureToggles.uiWebPolicyInstruction(): String = if (!get(ServiceUiWeb)) {
    "Web documents are disabled. Use native nodes or explain the required web document setting; " +
        "do not claim HTML was rendered."
} else {
    "Web documents can load public HTTPS assets and execute scripts. Showing or previewing a document " +
        "uses the profile permission setting; external scripts see page data and its allowed bridge."
}
