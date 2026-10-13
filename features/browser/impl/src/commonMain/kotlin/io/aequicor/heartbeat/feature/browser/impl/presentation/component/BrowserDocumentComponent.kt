package io.aequicor.heartbeat.feature.browser.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentRegistry
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserDocumentSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Decompose entry of a document route: the open document [id], or null once it closes or when it is unknown. */
internal class BrowserDocumentComponent(
    context: ComponentContext,
    id: String,
    registry: BrowserDocumentRegistry,
    scope: CoroutineScope,
) : ComponentContext by context {
    val surface: StateFlow<BrowserDocumentSurface?> = registry.documents
        .map { it[id]?.let(::BrowserDocumentSurface) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, registry.documents.value[id]?.let(::BrowserDocumentSurface))
}
