package io.aequicor.heartbeat.feature.adaptivesubagents.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbMenu
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.ConnectionUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.CostModeUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.ModelOptionUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.PriorityUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.SubagentsErrorUi
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.SubagentsModel
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.SubagentsScreenIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.SubagentsScreenState
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.Res
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_back
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_cost_free
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_cost_local
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_cost_menu
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_cost_metered
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_cost_subscription
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_cross_engine
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_cross_engine_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_default_model
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_dismiss
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_load_failed
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_loading
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_model_menu
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_model_unavailable
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_no_connections
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_priority_balanced
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_priority_balanced_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_priority_cost
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_priority_cost_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_priority_quality
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_priority_quality_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_priority_speed
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_priority_speed_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_researcher
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_researcher_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_retry
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_router
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_router_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_save_failed
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_section_costs
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_section_costs_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_section_selection
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_section_selection_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_section_service
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_section_service_hint
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.resources.subagents_title
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** The adaptive subagents section: selection preferences, service models and connection billing. */
@Composable
internal fun SubagentsScreen(model: SubagentsModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(SubagentsScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    SubagentsContent(state, model.store::intent, onBack, modifier)
}

/** Stateless section screen. */
@Composable
internal fun SubagentsContent(
    state: SubagentsScreenState,
    onIntent: (SubagentsScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("adaptive-subagents"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                stringResource(Res.string.subagents_title),
                leadingInset = if (HbTheme.dimensions.isDesktop) {
                    HbTheme.spacing.m
                } else {
                    HbTheme.dimensions.titlebarLeadingInset
                },
                navigation = {
                    HbIconButton(HbIcons.ArrowLeft, stringResource(Res.string.subagents_back), onBack)
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize(),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                sectionItems(state, onIntent)
            }
        }
    }
}

private fun LazyListScope.sectionItems(state: SubagentsScreenState, onIntent: (SubagentsScreenIntent) -> Unit) {
    state.error?.let { error -> item(key = "error") { ErrorBanner(error, onIntent) } }
    if (!state.isLoaded) {
        if (state.error != SubagentsErrorUi.LoadFailed) {
            item(key = "loading") { HbLoadingState(stringResource(Res.string.subagents_loading)) }
        }
        return
    }
    item(key = "selection") { SelectionSection(state, onIntent) }
    item(key = "service") { ServiceSection(state, onIntent) }
    item(key = "costs") { CostsSection(state.connections, onIntent) }
    item(key = "models") { ModelsSection(state, onIntent) }
    item(key = "trial") { TrialSection(state.trial, onIntent) }
}

@Composable
private fun SelectionSection(
    state: SubagentsScreenState,
    onIntent: (SubagentsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.subagents_section_selection),
        modifier.testTag("adaptive-subagents-selection"),
        description = stringResource(Res.string.subagents_section_selection_hint),
    ) {
        PriorityUi.entries.forEach { priority ->
            val isSelected = priority == state.priority
            HbSettingsRow(
                stringResource(priority.title()),
                Modifier.fillMaxWidth().semantics { selected = isSelected }
                    .testTag("adaptive-subagents-priority-${priority.name}"),
                description = stringResource(priority.hint()),
                onClick = { onIntent(SubagentsScreenIntent.SelectPriority(priority)) },
                isSelected = isSelected,
            ) {
                if (isSelected) HbIcon(HbIcons.Check, contentDescription = null)
            }
            HbDivider()
        }
        val crossEngine = stringResource(Res.string.subagents_cross_engine)
        HbSettingsRow(
            crossEngine,
            Modifier.fillMaxWidth(),
            description = stringResource(Res.string.subagents_cross_engine_hint),
        ) {
            HbSwitch(
                state.isCrossEngineAllowed,
                { onIntent(SubagentsScreenIntent.SetCrossEngine(it)) },
                crossEngine,
                Modifier.testTag("adaptive-subagents-cross-engine"),
            )
        }
    }
}

@Composable
private fun ServiceSection(
    state: SubagentsScreenState,
    onIntent: (SubagentsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.subagents_section_service),
        modifier.testTag("adaptive-subagents-service"),
        description = stringResource(Res.string.subagents_section_service_hint),
    ) {
        ModelChoiceRow(
            stringResource(Res.string.subagents_router),
            stringResource(Res.string.subagents_router_hint),
            state.models,
            state.routerModel,
            "router",
            { onIntent(SubagentsScreenIntent.SelectRouterModel(it)) },
        )
        HbDivider()
        ModelChoiceRow(
            stringResource(Res.string.subagents_researcher),
            stringResource(Res.string.subagents_researcher_hint),
            state.models,
            state.researcherModel,
            "researcher",
            { onIntent(SubagentsScreenIntent.SelectResearcherModel(it)) },
        )
    }
}

@Composable
private fun ModelChoiceRow(
    title: String,
    hint: String,
    models: ImmutableList<ModelOptionUi>,
    selected: String?,
    tag: String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val default = stringResource(Res.string.subagents_default_model)
    val options = remember(models, default) {
        (listOf(DEFAULT_ID to default) + models.map { it.id to it.title }).toImmutableList()
    }
    HbSettingsRow(title, modifier.fillMaxWidth(), description = hint) {
        ChoiceMenu(
            options,
            selected ?: DEFAULT_ID,
            stringResource(Res.string.subagents_model_menu),
            "adaptive-subagents-$tag",
            { onSelect(it.takeUnless { id -> id == DEFAULT_ID }) },
        )
    }
}

@Composable
private fun CostsSection(
    connections: ImmutableList<ConnectionUi>,
    onIntent: (SubagentsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.subagents_section_costs),
        modifier.testTag("adaptive-subagents-costs"),
        description = stringResource(Res.string.subagents_section_costs_hint),
    ) {
        if (connections.isEmpty()) HbEmptyState(stringResource(Res.string.subagents_no_connections))
        val labels = CostModeUi.entries.map { it.name to stringResource(it.title()) }.toImmutableList()
        connections.forEachIndexed { index, connection ->
            if (index > 0) HbDivider()
            HbSettingsRow(connection.title, Modifier.fillMaxWidth()) {
                ChoiceMenu(
                    labels,
                    connection.cost.name,
                    stringResource(Res.string.subagents_cost_menu),
                    "adaptive-subagents-cost-${connection.id}",
                    { onIntent(SubagentsScreenIntent.SelectCost(connection.id, CostModeUi.valueOf(it))) },
                )
            }
        }
    }
}

/** A button naming the current option that opens a menu of [options] (id to label). */
@Composable
internal fun ChoiceMenu(
    options: ImmutableList<Pair<String, String>>,
    selected: String,
    label: String,
    tag: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    Box(modifier) {
        HbButton(
            options.firstOrNull { it.first == selected }?.second
                ?: stringResource(Res.string.subagents_model_unavailable),
            { isExpanded = true },
            Modifier.widthIn(max = HbTheme.dimensions.composerMenuMaxWidth).testTag(tag),
            style = HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
        )
        HbMenu(
            options.map { (id, title) -> HbMenuItem(id, title, isChecked = id == selected) }.toImmutableList(),
            isExpanded,
            { isExpanded = false },
            { id ->
                isExpanded = false
                onSelect(id)
            },
            label,
        )
    }
}

@Composable
private fun ErrorBanner(
    error: SubagentsErrorUi,
    onIntent: (SubagentsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLoad = error == SubagentsErrorUi.LoadFailed
    val message = if (isLoad) Res.string.subagents_load_failed else Res.string.subagents_save_failed
    HbBanner(stringResource(message), modifier.testTag("adaptive-subagents-error")) {
        HbButton(
            stringResource(if (isLoad) Res.string.subagents_retry else Res.string.subagents_dismiss),
            { onIntent(if (isLoad) SubagentsScreenIntent.Reload else SubagentsScreenIntent.DismissError) },
            style = HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
        )
    }
}

internal fun PriorityUi.title(): StringResource = when (this) {
    PriorityUi.Quality -> Res.string.subagents_priority_quality
    PriorityUi.Balanced -> Res.string.subagents_priority_balanced
    PriorityUi.Speed -> Res.string.subagents_priority_speed
    PriorityUi.Cost -> Res.string.subagents_priority_cost
}

private fun PriorityUi.hint(): StringResource = when (this) {
    PriorityUi.Quality -> Res.string.subagents_priority_quality_hint
    PriorityUi.Balanced -> Res.string.subagents_priority_balanced_hint
    PriorityUi.Speed -> Res.string.subagents_priority_speed_hint
    PriorityUi.Cost -> Res.string.subagents_priority_cost_hint
}

private fun CostModeUi.title(): StringResource = when (this) {
    CostModeUi.Metered -> Res.string.subagents_cost_metered
    CostModeUi.Subscription -> Res.string.subagents_cost_subscription
    CostModeUi.Local -> Res.string.subagents_cost_local
    CostModeUi.Free -> Res.string.subagents_cost_free
}

private const val DEFAULT_ID = "default"

@Preview
@Composable
private fun SubagentsLightPreview() {
    HbTheme(darkTheme = false) { SubagentsContent(previewState, {}, {}) }
}

@Preview
@Composable
private fun SubagentsDarkPreview() {
    HbTheme(darkTheme = true) { SubagentsContent(previewState, {}, null) }
}

private val previewState = SubagentsScreenState(
    isLoaded = true,
    priority = PriorityUi.Speed,
    models = persistentListOf(ModelOptionUi("m", "Claude Haiku 4.5 · Claude")),
    connections = persistentListOf(ConnectionUi("api", "Claude · Рабочий ключ", CostModeUi.Subscription)),
)
