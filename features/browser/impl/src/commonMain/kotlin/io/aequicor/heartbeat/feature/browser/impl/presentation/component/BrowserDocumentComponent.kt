package io.aequicor.heartbeat.feature.browser.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentNatives
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentRegistry
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserDocumentSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Decompose entry of a document route: the open document [id] drawn with [natives], or null once it closes or when
 * it is unknown.
 */
internal class BrowserDocumentComponent(
    context: ComponentContext,
    id: String,
    registry: BrowserDocumentRegistry,
    scope: CoroutineScope,
    natives: BrowserDocumentNatives = BrowserDocumentNatives.None,
) : ComponentContext by context {
    val surface: StateFlow<BrowserDocumentSurface?> = registry.documents
        .map { documents -> documents[id]?.let { BrowserDocumentSurface(it, natives) } }
        .distinctUntilChanged()
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            registry.documents.value[id]?.let { BrowserDocumentSurface(it, natives) },
        )
}
