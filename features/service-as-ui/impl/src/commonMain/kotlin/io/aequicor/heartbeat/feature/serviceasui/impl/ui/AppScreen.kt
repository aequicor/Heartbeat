package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDialogWidth
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.hbSurface
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppHostIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppHostModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app.AppHostState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceState
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_app_expand
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_close
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_loading
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** One retained host renders the app either on the start screen or in a modal, never in both places at once. */
@Composable
internal fun AppScreen(
    model: AppHostModel,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    val state by model.state.collectAsState()
    val surfaces by model.surfaces.collectAsState()
    val surface = state.key?.let(surfaces::get)
    val scrolls = remember(model) { mutableMapOf<String, ScrollState>() }
    val scroll = remember(model, state.key) {
        state.key?.let { key ->
            scrolls.getOrPut(key) {
                scrolls.clear()
                ScrollState(0)
            }
        } ?: ScrollState(0)
    }
    val content by produceState(SurfaceState(), surface) {
        surface?.store?.collect { states.collect { value = it } }
    }
    val hasDocument = content.root?.containsWeb() == true
    val body: @Composable (Modifier) -> Unit = { size ->
        if (surface == null) {
            HbLoadingState(stringResource(Res.string.service_ui_loading), size)
        } else {
            SurfaceScreen(
                surface,
                size.clip(HbTheme.shapes.medium),
                documents,
                isApp = true,
                scrollState = scroll,
                isContentFill = !state.isExpanded,
            )
        }
    }
    AppHostView(state, hasDocument, model::intent, modifier, content.root is UiNodeUi.Web, body)
}

/**
 * Native full-start/modal chrome around one bounded application body. A sole document owns its inner spacing;
 * native and mixed trees keep the regular dialog header and insets.
 */
// Start and modal have separate composition mounts. The store, scroll and document live above both.
@Suppress("ContentSlotReused")
@Composable
internal fun AppHostView(
    state: AppHostState,
    hasDocument: Boolean,
    onIntent: (AppHostIntent) -> Unit,
    modifier: Modifier = Modifier,
    isDocumentOnly: Boolean = false,
    body: @Composable (Modifier) -> Unit,
) {
    if (state.isExpanded) {
        HbDialog(
            title = state.title,
            onDismissRequest = { onIntent(AppHostIntent.Close(state.sequence)) },
            width = HbDialogWidth.Wide,
            isContentScrollable = false,
            isCompact = true,
            isContentEdgeToEdge = isDocumentOnly,
            isTitleVisible = !hasDocument,
            headerActions = {
                HbIconButton(
                    HbIcons.Close,
                    stringResource(Res.string.service_ui_close),
                    { onIntent(AppHostIntent.Close(state.sequence)) },
                    modifier = if (isDocumentOnly) {
                        Modifier.hbSurface(HbTheme.colors.surface, HbTheme.shapes.small)
                    } else {
                        Modifier
                    },
                )
            },
        ) { body(Modifier.fillMaxWidth()) }
    } else if (state.isStart) {
        HbColumn(
            modifier.fillMaxSize().hbSurface(HbTheme.colors.surface, HbTheme.shapes.large)
                .semantics { paneTitle = state.title },
            gap = HbTheme.spacing.s,
        ) {
            HbRow(Modifier.fillMaxWidth()) {
                HbText(if (hasDocument) "" else state.title, Modifier.weight(1f), maxLines = 1)
                HbIconButton(
                    HbIcons.Maximize,
                    stringResource(Res.string.service_ui_app_expand),
                    { onIntent(AppHostIntent.Open) },
                )
            }
            HbColumn(Modifier.weight(1f).fillMaxWidth()) { body(Modifier.fillMaxSize()) }
        }
    }
}
