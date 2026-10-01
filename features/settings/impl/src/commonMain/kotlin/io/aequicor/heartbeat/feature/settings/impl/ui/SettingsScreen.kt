package io.aequicor.heartbeat.feature.settings.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.core.navigation.compose.NavStack
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbNavigationHeader
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbWindowDragArea
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.settings.impl.presentation.component.SettingsComponent
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.SettingsScreenState
import io.aequicor.heartbeat.feature.settings.impl.presentation.store.SettingsSectionUi
import io.aequicor.heartbeat.feature.settings.impl.resources.Res
import io.aequicor.heartbeat.feature.settings.impl.resources.settings_back
import io.aequicor.heartbeat.feature.settings.impl.resources.settings_section_computer_use
import io.aequicor.heartbeat.feature.settings.impl.resources.settings_section_feature_flags
import io.aequicor.heartbeat.feature.settings.impl.resources.settings_section_models
import io.aequicor.heartbeat.feature.settings.impl.resources.settings_section_search
import io.aequicor.heartbeat.feature.settings.impl.resources.settings_title
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

@Composable
internal fun SettingsScreen(component: SettingsComponent, modifier: Modifier = Modifier) {
    val state by produceState(SettingsScreenState(), component.model) {
        component.model.store.collect { states.collect { value = it } }
    }
    SettingsContent(state, component::select, component::back, component::close, modifier) {
        NavStack(component.sections, it)
    }
}

/**
 * The settings window in the studio frame. Wide: the section list in the studio sidebar style (28dp rows, quiet
 * selected fill, "back" on top closes the window) next to a pane with the section's header and content. Compact:
 * the list, then one section with a back arrow. Esc and the compact back arrow leave a nested flow first, then the
 * section (compact), then the window.
 * [section] renders the embedded content of the selected section.
 */
@Composable
internal fun SettingsContent(
    state: SettingsScreenState,
    onSelect: (SettingsSectionUi) -> Unit,
    onBack: (isCompact: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    section: @Composable (Modifier) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(focus) {
        // Wait until the focus target is attached and laid out.
        withFrameNanos { }
        focus.requestFocus()
    }
    HbBoxWithConstraints(modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("settings")) {
        val isCompact = maxWidth < HbTheme.dimensions.compactBreakpoint
        Box(
            Modifier.fillMaxSize()
                .onKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                        back(isCompact)
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(focus)
                .focusable(),
        ) {
            when {
                !isCompact -> WideSettings(state, onSelect, onClose, section)
                state.isCompactListShown -> CompactList(state, onSelect, { back(true) })
                else -> CompactSection(state, { back(true) }, section)
            }
        }
    }
}

@Composable
private fun WideSettings(
    state: SettingsScreenState,
    onSelect: (SettingsSectionUi) -> Unit,
    onBack: () -> Unit,
    section: @Composable (Modifier) -> Unit,
) {
    HbRow(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
        SectionList(
            state,
            onSelect,
            onBack,
            Modifier.width(HbTheme.dimensions.sidebarWidth).fillMaxHeight().background(HbTheme.surfaces.sidebar),
            isSelectionShown = true,
        )
        HbColumn(Modifier.weight(1f).fillMaxHeight(), gap = HbTheme.spacing.none) {
            HbPaneHeader(state.selected?.let { sectionTitle(it) }.orEmpty(), background = HbTheme.surfaces.backdrop)
            Box(Modifier.weight(1f).fillMaxWidth().testTag("settings-section")) { section(Modifier.fillMaxSize()) }
        }
    }
}

@Composable
private fun CompactList(state: SettingsScreenState, onSelect: (SettingsSectionUi) -> Unit, onBack: () -> Unit) {
    HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
        HbPaneHeader(
            stringResource(Res.string.settings_title),
            leadingInset = if (HbTheme.dimensions.isDesktop) {
                HbTheme.spacing.m
            } else {
                HbTheme.dimensions.titlebarLeadingInset
            },
            background = HbTheme.surfaces.backdrop,
            navigation = { BackButton(onBack) },
        )
        SectionRows(state, onSelect, isSelectionShown = false, Modifier.padding(HbTheme.spacing.m))
    }
}

@Composable
private fun CompactSection(state: SettingsScreenState, onBack: () -> Unit, section: @Composable (Modifier) -> Unit) {
    HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
        HbPaneHeader(
            state.selected?.let { sectionTitle(it) }.orEmpty(),
            leadingInset = if (HbTheme.dimensions.isDesktop) {
                HbTheme.spacing.m
            } else {
                HbTheme.dimensions.titlebarLeadingInset
            },
            background = HbTheme.surfaces.backdrop,
            navigation = { BackButton(onBack) },
        )
        Box(Modifier.weight(1f).fillMaxWidth().testTag("settings-section")) { section(Modifier.fillMaxSize()) }
    }
}

/** Sidebar of the wide window: one caption-safe header followed by section rows. */
@Composable
private fun SectionList(
    state: SettingsScreenState,
    onSelect: (SettingsSectionUi) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isSelectionShown: Boolean = true,
) {
    val dimensions = HbTheme.dimensions
    HbColumn(modifier.testTag("settings-sections"), gap = HbTheme.spacing.none) {
        if (dimensions.isDesktop) {
            HbPaneHeader(
                stringResource(Res.string.settings_title),
                background = HbTheme.surfaces.sidebar,
                navigation = { BackButton(onBack) },
            )
        } else {
            HbWindowDragArea(Modifier.fillMaxWidth().padding(top = dimensions.titlebarInset)) {
                HbColumn(Modifier.fillMaxWidth().padding(HbTheme.spacing.m), gap = HbTheme.spacing.xxs) {
                    HbNavigationItem(
                        stringResource(Res.string.settings_back),
                        onBack,
                        Modifier.testTag("settings-back"),
                        icon = HbIcons.ArrowLeft,
                        minHeight = dimensions.navigationRowHeight,
                    )
                    HbNavigationHeader(
                        stringResource(Res.string.settings_title),
                        minHeight = dimensions.sidebarHeadingHeight,
                    )
                }
            }
        }
        SectionRows(
            state,
            onSelect,
            isSelectionShown,
            Modifier.padding(horizontal = HbTheme.spacing.m).hbVerticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun SectionRows(
    state: SettingsScreenState,
    onSelect: (SettingsSectionUi) -> Unit,
    isSelectionShown: Boolean,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.xxs) {
        state.sections.forEach { section ->
            HbNavigationItem(
                sectionTitle(section),
                { onSelect(section) },
                Modifier.testTag("settings-section:${section.name}"),
                icon = section.icon(),
                isSelected = isSelectionShown && section == state.selected,
                role = Role.Tab,
                minHeight = HbTheme.dimensions.navigationRowHeight,
                selectedBackground = HbTheme.surfaces.selected,
                selectedForeground = HbTheme.surfaces.onSelected,
                trailingContent = {
                    if (!isSelectionShown) HbIcon(HbIcons.ChevronRight, null, tint = HbTheme.colors.textSecondary)
                },
            )
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    HbIconButton(HbIcons.ArrowLeft, stringResource(Res.string.settings_back), onBack, Modifier.testTag("settings-back"))
}

@Composable
private fun sectionTitle(section: SettingsSectionUi): String = stringResource(
    when (section) {
        SettingsSectionUi.Models -> Res.string.settings_section_models
        SettingsSectionUi.Search -> Res.string.settings_section_search
        SettingsSectionUi.ComputerUse -> Res.string.settings_section_computer_use
        SettingsSectionUi.FeatureFlags -> Res.string.settings_section_feature_flags
    },
)

private fun SettingsSectionUi.icon(): ImageVector = when (this) {
    SettingsSectionUi.Models -> HbIcons.Layers
    SettingsSectionUi.Search -> HbIcons.Search
    SettingsSectionUi.ComputerUse -> HbIcons.Layers
    SettingsSectionUi.FeatureFlags -> HbIcons.Sliders
}

@Preview
@Composable
private fun SettingsLightPreview() {
    HbTheme(darkTheme = false) { SettingsContent(previewState, {}, {}, {}) { } }
}

@Preview
@Composable
private fun SettingsDarkPreview() {
    HbTheme(darkTheme = true) { SettingsContent(previewState, {}, {}, {}) { } }
}

private val previewState = SettingsScreenState(
    sections = persistentListOf(SettingsSectionUi.Models, SettingsSectionUi.Search, SettingsSectionUi.FeatureFlags),
    selected = SettingsSectionUi.Models,
)
