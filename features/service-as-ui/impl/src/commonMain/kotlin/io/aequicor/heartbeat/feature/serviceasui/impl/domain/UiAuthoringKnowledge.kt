package io.aequicor.heartbeat.feature.serviceasui.impl.domain

/** Read-only authoring knowledge shipped with the application, independent of a profile or user library. */
internal fun interface UiAuthoringKnowledge {
    suspend fun read(document: UiAuthoringDocument): String
}

/** Closed resource catalog: agent arguments select a document, never a filesystem path. */
internal enum class UiAuthoringDocument(val topic: String) {
    MiniAppDesign("mini-app-design"),
    ServiceAsUi("service-as-ui"),
    Layout("service-as-ui/layout"),
    ActionsData("service-as-ui/actions-data"),
    WebView("service-as-ui/webview"),
    VisualPatterns("service-as-ui/visual-patterns"),
    Examples("service-as-ui/examples"),
}
