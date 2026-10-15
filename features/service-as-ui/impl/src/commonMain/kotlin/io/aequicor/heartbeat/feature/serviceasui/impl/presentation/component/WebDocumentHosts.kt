package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.router.items.Items
import com.arkivanov.decompose.router.items.Items.ActiveLifecycleState
import com.arkivanov.decompose.router.items.ItemsNavigation
import com.arkivanov.decompose.router.items.childItems
import com.arkivanov.decompose.router.items.navigate
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPresentation
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentRoute
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Child hosts of the browser documents the drawn surfaces of [surfaces] show, by document route id: one stack with
 * the [BrowserDocumentRoute] of each, created when a surface opens a document and destroyed when it closes it. Owned
 * by [context], the component of a place, card or editor preview.
 */
@OptIn(ExperimentalDecomposeApi::class, ExperimentalCoroutinesApi::class)
internal class WebDocumentHosts(
    context: ComponentContext,
    navigator: Navigator,
    hosts: NavHostFactory,
    scope: CoroutineScope,
    surfaces: StateFlow<Map<String, SurfaceModel>>,
    isApp: Boolean = false,
) {
    private val log = Log.tag("ServiceUiWebHosts")
    private val navigation = ItemsNavigation<String>()
    private val items = context.childItems(
        source = navigation,
        serializer = null,
        initialItems = { Items() },
        key = "web-documents",
    ) { id, child ->
        hosts.stack(
            context = child,
            parent = navigator,
            name = "web-document-$id",
            initial = listOf(
                BrowserDocumentRoute(
                    id,
                    if (isApp) BrowserDocumentPresentation.Content else BrowserDocumentPresentation.Document,
                ),
            ),
            global = GlobalRoutes.Only(setOf(BrowserDocumentRoute::class)),
        )
    }
    private val current = MutableStateFlow<ImmutableMap<String, StackHost>>(persistentMapOf())

    /** Hosts of the open documents by route id. */
    val documents: StateFlow<ImmutableMap<String, StackHost>> = current.asStateFlow()

    init {
        val watch = items.subscribe { children ->
            log.v { "Update web document hosts: ${children.activeItems.size}" }
            current.value = children.activeItems.mapValues { (_, child) -> child.first }.toImmutableMap()
        }
        val job = scope.launch {
            surfaces.flatMapLatest { it.values.documentIds() }.distinctUntilChanged().collect { ids ->
                navigation.navigate { Items(ids, ids.associateWith { ActiveLifecycleState.RESUMED }) }
            }
        }
        context.lifecycle.doOnDestroy {
            watch.cancel()
            job.cancel()
        }
    }
}

/** Route ids of the documents these surfaces show, in a stable order. */
private fun Collection<SurfaceModel>.documentIds(): Flow<List<String>> = if (isEmpty()) {
    flowOf(emptyList())
} else {
    combine(map { it.webDocuments }) { shown -> shown.asSequence().flatMap { it.values }.distinct().sorted().toList() }
}
