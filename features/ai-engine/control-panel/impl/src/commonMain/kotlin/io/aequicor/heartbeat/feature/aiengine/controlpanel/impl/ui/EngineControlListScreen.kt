package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlListIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlListModel
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlListState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineItemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_configurable
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_list_description
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_list_empty
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_list_engines
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_loading
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_off
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_version
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_version_unknown
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

@Composable
internal fun EngineControlListScreen(
    model: EngineControlListModel,
    title: String?,
    onOpen: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by produceState(EngineControlListState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    EngineControlListContent(state, title, { model.store.intent(it) }, onOpen, onBack, modifier)
}

/** Every registered engine as a settings row; a row opens the engine's panel. */
@Composable
internal fun EngineControlListContent(
    state: EngineControlListState,
    title: String?,
    onIntent: (EngineControlListIntent) -> Unit,
    onOpen: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ControlColumn(title, onBack, modifier.testTag("engine-control-list")) {
        HbText(
            stringResource(Res.string.control_list_description),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        when {
            state.hasLoadFailure -> LoadFailure({ onIntent(EngineControlListIntent.RetryLoad) })
            state.isLoading -> HbLoadingState(stringResource(Res.string.control_loading))
            state.isOff -> HbBanner(stringResource(Res.string.control_off), tone = HbTone.Warning)
            else -> EnginesSection(state, onOpen)
        }
    }
}

@Composable
private fun EnginesSection(state: EngineControlListState, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    HbSettingsSection(stringResource(Res.string.control_list_engines), modifier) {
        if (state.engines.isEmpty()) HbEmptyState(stringResource(Res.string.control_list_empty))
        HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.none) {
            state.engines.forEachIndexed { index, engine ->
                if (index > 0) HbDivider()
                EngineRow(engine, onClick = { onOpen(engine.id) })
            }
        }
    }
}

@Composable
private fun EngineRow(engine: EngineItemUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HbSettingsRow(
        engine.title,
        modifier.testTag("engine-control:${engine.id}"),
        description = engine.version?.let { stringResource(Res.string.control_version, it) }
            ?: stringResource(Res.string.control_version_unknown),
        onClick = onClick,
    ) {
        if (engine.isConfigurable) HbBadge(stringResource(Res.string.control_configurable), tone = HbTone.Brand)
        StatusBadge(engine.status)
        HbIcon(HbIcons.ChevronRight, contentDescription = null)
    }
}
