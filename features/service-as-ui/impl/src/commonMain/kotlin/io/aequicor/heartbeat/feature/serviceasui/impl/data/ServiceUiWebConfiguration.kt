package io.aequicor.heartbeat.feature.serviceasui.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWeb
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWebSafeMode
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** One settings projection for editor, live surfaces and disposable previews; model arguments never select it. */
internal fun FeatureToggles.uiWebConfiguration(isDesktop: Boolean): Flow<UiWebConfiguration> = combine(
    observe(ServiceUiEnabled),
    observe(ServiceUiWeb),
    observe(ServiceUiWebSafeMode),
) { enabled, web, safeMode ->
    UiWebConfiguration(
        isDesktop && enabled && web,
        if (safeMode) BrowserDocumentResourcePolicy.Isolated else BrowserDocumentResourcePolicy.ExternalAssets,
    )
}.distinctUntilChanged()

/** The effective resource policy is part of the immutable automatic-review snapshot. */
internal suspend fun FeatureToggles.uiWebResourcePolicy(): BrowserDocumentResourcePolicy {
    val isSafeMode = get(ServiceUiWebSafeMode)
    return if (isSafeMode) BrowserDocumentResourcePolicy.Isolated else BrowserDocumentResourcePolicy.ExternalAssets
}

/** Per-turn guidance reports actual persisted settings; the author cannot change them through tool arguments. */
internal suspend fun FeatureToggles.uiWebPolicyInstruction(): String {
    if (!get(ServiceUiWeb)) {
        return "Web documents are disabled. Use native nodes or explain the required web document setting; " +
            "do not claim HTML was rendered."
    }
    return when (uiWebResourcePolicy()) {
        BrowserDocumentResourcePolicy.Isolated ->
            "Current mini-app Safe Mode: on. External resources are blocked; use inline code and embedded assets."

        BrowserDocumentResourcePolicy.ExternalAssets ->
            "Current mini-app Safe Mode: off. Public HTTPS asset dependencies are permitted. " +
                "External scripts see page data and its allowed bridge; automatic review does not certify " +
                "third-party code."
    }
}
