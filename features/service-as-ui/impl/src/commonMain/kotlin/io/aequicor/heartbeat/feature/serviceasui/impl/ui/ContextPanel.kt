package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PlaceState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_context_actions
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_context_surfaces
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * Content of the host's floating summary. Multiple active owners share a selector instead of stacking applications.
 * The selected body alone is mounted: native content has one scroll; a web body owns its bounded viewport.
 * The spec provides its own heading, so the host does not repeat the surface title above it.
 */
@Composable
internal fun ContextPlaceContent(
    state: PlaceState,
    surfaces: ImmutableMap<String, SurfaceModel>,
    onIntent: (PlaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    if (state.surfaces.isEmpty() && state.launchers.isEmpty()) return
    var selected by remember { mutableStateOf<String?>(null) }
    val surface = state.surfaces.firstOrNull { it.key == selected } ?: state.surfaces.firstOrNull()
    ContextPanelFrame(modifier) {
        if (state.surfaces.size > 1 || state.launchers.isNotEmpty()) {
            HbRow(Modifier.fillMaxWidth().padding(HbTheme.spacing.m)) {
                if (state.surfaces.size > 1) {
                    var isExpanded by remember { mutableStateOf(false) }
                    HbMenuButton(
                        HbIcons.ChevronDown,
                        stringResource(Res.string.service_ui_context_surfaces),
                        state.surfaces.map { HbMenuItem(it.key, it.title, isChecked = it.key == surface?.key) }
                            .toImmutableList(),
                        isExpanded,
                        { isExpanded = it },
                        { selected = it },
                        Modifier.weight(1f),
                        label = surface?.title,
                    )
                }
                if (state.launchers.isNotEmpty()) {
                    var isExpanded by remember { mutableStateOf(false) }
                    val label = stringResource(Res.string.service_ui_context_actions)
                    HbMenuButton(
                        HbIcons.More,
                        label,
                        state.launchers.map { HbMenuItem(it.surface, it.title) }.toImmutableList(),
                        isExpanded,
                        { isExpanded = it },
                        { onIntent(PlaceIntent.Launch(it)) },
                        label = label.takeIf { surface == null },
                    )
                }
            }
        }
        val model = surface?.let { surfaces[it.key] }
        model?.let { shown ->
            key(surface.key) {
                SurfaceScreen(
                    shown,
                    Modifier.weight(1f, fill = false).fillMaxWidth(),
                    documents,
                    isApp = true,
                    isContentFill = false,
                    isBodyInset = true,
                )
            }
        }
    }
}

/** The host owns the card surface; its summary body adds no nested border or minimum content height. */
@Composable
internal fun ContextPanelFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    HbColumn(
        modifier.fillMaxWidth(),
        gap = HbTheme.spacing.none,
        content = content,
    )
}
