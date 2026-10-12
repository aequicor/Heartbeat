package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.TextStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceSurfaceUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_close
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_close_panel
import kotlinx.collections.immutable.ImmutableMap
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** The surfaces of a place; a panel adds its header and scrolls, a slot leaves bounds and scrolling to its host. */
@Composable
internal fun PlaceScreen(model: PlaceModel, modifier: Modifier = Modifier) {
    val state by produceState(PlaceState(), model) { model.store.collect { states.collect { value = it } } }
    val surfaces by model.surfaces.collectAsState()
    PlaceView(state, surfaces, model.store::intent, model.isPanel, modifier)
}

/** The drawn surfaces from [surfaces], launchers and the modal of [state]. */
@Composable
internal fun PlaceView(
    state: PlaceState,
    surfaces: ImmutableMap<String, SurfaceModel>,
    onIntent: (PlaceIntent) -> Unit,
    isPanel: Boolean,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.m) {
        state.panelTitle?.takeIf { isPanel }?.let { title -> PanelHeader(title, onIntent) }
        val scroll = rememberScrollState()
        val body = if (isPanel) Modifier.fillMaxWidth().hbVerticalScroll(scroll) else Modifier
        HbColumn(body, gap = HbTheme.spacing.m) {
            state.surfaces.forEach { surface ->
                key(surface.key) { PlacedSurfaceView(surface, surfaces) }
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
    ) { PlacedSurfaceView(modal, surfaces) }
}

@Composable
private fun PanelHeader(title: String, onIntent: (PlaceIntent) -> Unit) {
    HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbText(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = TextStyleUi.Title.style(),
            maxLines = 1,
        )
        HbIconButton(
            HbIcons.Close,
            stringResource(Res.string.service_ui_close_panel),
            { onIntent(PlaceIntent.ClosePanel) },
        )
    }
}

@Composable
private fun PlacedSurfaceView(surface: PlaceSurfaceUi, surfaces: ImmutableMap<String, SurfaceModel>) {
    surfaces[surface.key]?.let { SurfaceScreen(it.store) }
}
