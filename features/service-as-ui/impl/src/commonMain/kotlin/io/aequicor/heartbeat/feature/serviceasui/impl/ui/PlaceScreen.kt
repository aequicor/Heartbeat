package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceSurfaceUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_close
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_close_panel
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_panel_title
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/**
 * The surfaces of a place; a panel adds its header and scrolls, a slot leaves bounds and scrolling to its host.
 * [documents] are the browser documents the place hosts for its surfaces, by route id.
 */
@Composable
internal fun PlaceScreen(
    model: PlaceModel,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    val state by produceState(PlaceState(), model) { model.store.collect { states.collect { value = it } } }
    val surfaces by model.surfaces.collectAsState()
    PlaceView(state, surfaces, model.store::intent, model.isPanel, modifier, documents)
}

/** The drawn surfaces from [surfaces], launchers and the modal of [state]. */
@Composable
internal fun PlaceView(
    state: PlaceState,
    surfaces: ImmutableMap<String, SurfaceModel>,
    onIntent: (PlaceIntent) -> Unit,
    isPanel: Boolean,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    // An empty slot emits nothing, so the host's spacing around it stays as without interfaces.
    if (!isPanel && state.isEmpty) return
    if (isPanel) {
        // A panel can cover the whole chat on a phone: its header always offers a way out.
        HbColumn(modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
            HbPaneHeader(
                state.panelTitle ?: stringResource(Res.string.service_ui_panel_title),
                actions = {
                    HbIconButton(
                        HbIcons.Close,
                        stringResource(Res.string.service_ui_close_panel),
                        { onIntent(PlaceIntent.ClosePanel) },
                    )
                },
            )
            PlaceContent(
                state,
                surfaces,
                onIntent,
                Modifier.weight(1f).fillMaxWidth().hbVerticalScroll(rememberScrollState()).padding(HbTheme.spacing.xl),
                documents,
            )
        }
    } else {
        PlaceContent(state, surfaces, onIntent, modifier.fillMaxWidth(), documents)
    }
    val modal = state.modal ?: return
    HbDialog(
        title = modal.title,
        onDismissRequest = { onIntent(PlaceIntent.CloseModal) },
        actions = {
            HbButton(
                stringResource(Res.string.service_ui_close),
                { onIntent(PlaceIntent.CloseModal) },
                style = HbButtonStyle.Ghost,
            )
        },
    ) { PlacedSurfaceView(modal, surfaces, documents) }
}

/** The surfaces and launchers of a place. */
@Composable
private fun PlaceContent(
    state: PlaceState,
    surfaces: ImmutableMap<String, SurfaceModel>,
    onIntent: (PlaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    HbColumn(modifier, gap = HbTheme.spacing.m) {
        state.surfaces.forEach { surface ->
            key(surface.key) { PlacedSurfaceView(surface, surfaces, documents) }
        }
        if (state.launchers.isNotEmpty()) {
            HbFlowRow(gap = HbTheme.spacing.s) {
                state.launchers.forEach { launcher ->
                    key(launcher.surface) {
                        HbChip(
                            label = launcher.title,
                            onClick = { onIntent(PlaceIntent.Launch(launcher.surface)) },
                            trailingIcon = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlacedSurfaceView(
    surface: PlaceSurfaceUi,
    surfaces: ImmutableMap<String, SurfaceModel>,
    documents: ImmutableMap<String, ComposableComponent>,
) {
    surfaces[surface.key]?.let { SurfaceScreen(it, documents) }
}
