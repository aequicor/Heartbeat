package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.core.navigation.compose.NavStack
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbPressableRow
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component.EngineControlBackTarget
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component.EngineControlListComponent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component.EngineControlPanelComponent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlListIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlListState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineItemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_engine_builtin
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_engine_builtin_status
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_engine_connected
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_engine_disconnected
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_engine_select
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_list_empty
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_list_engines
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_loading
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_off
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Engine browser: settings host owns the first column; this component owns list and details. */
internal class EngineControlListUiComponent(private val component: EngineControlListComponent) : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        val state by produceState(EngineControlListState(), component.model) {
            component.model.store.collect { states.collect { value = it } }
        }
        val stack by component.details.stack.subscribeAsState()
        val selected = remember(stack) { component.selectedEngine() }
        EngineControlKeyboardScope(selected, component::back, modifier) {
            HbBoxWithConstraints(Modifier.fillMaxSize()) {
                val isWide = HbTheme.dimensions.isDesktop &&
                    maxWidth >= HbTheme.dimensions.compactBreakpoint + HbTheme.dimensions.engineListWidth
                when {
                    isWide -> EngineControlBrowserContent(
                        state,
                        selected,
                        component::open,
                        onRetry = { component.model.store.intent(EngineControlListIntent.RetryLoad) },
                    ) {
                        NavStack(component.details, Modifier.fillMaxSize())
                    }

                    selected != null -> NavStack(component.details, Modifier.fillMaxSize())

                    else -> EngineControlListScreen(
                        component.model,
                        stringResource(Res.string.control_list_engines).takeIf { !component.isEmbedded },
                        component::open,
                        component::close.takeIf { !component.isEmbedded },
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Owns focus after engine selection, allowing Escape to reach the selected panel's draft guard. */
@Composable
internal fun EngineControlKeyboardScope(
    selectedEngine: String?,
    onBack: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(selectedEngine) {
        if (selectedEngine != null) {
            // The selected pane and its focus target must be attached before focus moves into it.
            withFrameNanos { }
            focus.requestFocus()
        }
    }
    Box(
        modifier.fillMaxSize().focusRequester(focus).onKeyEvent {
            it.key == Key.Escape && it.type == KeyEventType.KeyUp && onBack()
        }.focusable(),
    ) { content() }
}

/** List/details layout retaining each engine's drafts while the user changes selection. */
@Composable
internal fun EngineControlBrowserContent(
    state: EngineControlListState,
    selectedEngine: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    details: @Composable () -> Unit,
) {
    HbRow(modifier.fillMaxSize().testTag("engine-browser"), gap = HbTheme.spacing.none) {
        HbColumn(
            Modifier.width(HbTheme.dimensions.engineListWidth).fillMaxHeight()
                .background(HbTheme.surfaces.backdrop).testTag("engine-browser-list"),
            gap = HbTheme.spacing.s,
        ) {
            HbPaneHeader(stringResource(Res.string.control_list_engines))
            HbLazyColumn(
                Modifier.weight(1f).fillMaxSize().testTag("engine-browser-list-scroll"),
                gap = HbTheme.spacing.none,
                contentPadding = PaddingValues(HbTheme.spacing.none),
            ) {
                engineBrowserList(state, selectedEngine, onOpen, onRetry)
            }
        }
        HbDivider(Modifier.fillMaxHeight(), isVertical = true)
        Box(Modifier.weight(1f).fillMaxHeight().testTag("engine-browser-details")) { details() }
    }
}

/** Loading and failure stay in the list area while an existing engine panel remains open. */
private fun androidx.compose.foundation.lazy.LazyListScope.engineBrowserList(
    state: EngineControlListState,
    selectedEngine: String?,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.hasLoadFailure -> item { LoadFailure(onRetry) }

        state.isLoading -> item { HbLoadingState(stringResource(Res.string.control_loading)) }

        state.isOff -> item { HbBanner(stringResource(Res.string.control_off), tone = HbTone.Warning) }

        state.engines.isEmpty() -> item { HbEmptyState(stringResource(Res.string.control_list_empty)) }

        else -> items(state.engines, key = EngineItemUi::id) {
            EngineBrowserRow(it, it.id == selectedEngine, onOpen)
        }
    }
}

/** One engine: the connection status has its own color and is separate from selection. */
@Composable
private fun EngineBrowserRow(engine: EngineItemUi, isSelected: Boolean, onOpen: (String) -> Unit) {
    val title = if (engine.isBuiltIn) stringResource(Res.string.control_engine_builtin) else engine.title
    val status = when {
        engine.isBuiltIn -> stringResource(Res.string.control_engine_builtin_status)
        engine.isConnected -> stringResource(Res.string.control_engine_connected)
        else -> stringResource(Res.string.control_engine_disconnected)
    }
    HbPressableRow(
        { onOpen(engine.id) },
        Modifier.padding(horizontal = HbTheme.spacing.s)
            .background(if (isSelected) HbTheme.surfaces.selected else HbTheme.surfaces.backdrop)
            .semantics { selected = isSelected }.testTag("engine-control:${engine.id}"),
    ) {
        HbColumn(Modifier.weight(1f), gap = HbTheme.spacing.xxs) {
            HbText(title, style = HbTheme.typography.label, maxLines = 1)
            HbText(
                status,
                style = HbTheme.typography.caption,
                color = if (engine.isConnected && !engine.isBuiltIn) {
                    HbTheme.surfaces.success
                } else {
                    HbTheme.colors.textSecondary
                },
                maxLines = 1,
            )
        }
    }
}

/** Empty right pane before the first engine selection. */
internal class EngineControlBlankUiComponent : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        Box(modifier.fillMaxSize().padding(HbTheme.spacing.xl)) {
            HbText(stringResource(Res.string.control_engine_select), color = HbTheme.colors.textSecondary)
        }
    }
}

/** Rendering adapter of one engine's panel, including drill-down back navigation. */
internal class EngineControlPanelUiComponent(private val component: EngineControlPanelComponent) :
    ComposableComponent,
    EngineControlBackTarget {
    override fun requestBack() = component.model.store.intent(EngineControlPanelIntent.Back)

    @Composable
    override fun Content(modifier: Modifier) {
        HbBoxWithConstraints(modifier.fillMaxSize()) {
            val isEmbedded = component.isEmbedded && HbTheme.dimensions.isDesktop &&
                maxWidth >= HbTheme.dimensions.compactBreakpoint
            EngineControlPanelScreen(component.model, component::close, Modifier.fillMaxSize(), isEmbedded)
        }
    }
}
