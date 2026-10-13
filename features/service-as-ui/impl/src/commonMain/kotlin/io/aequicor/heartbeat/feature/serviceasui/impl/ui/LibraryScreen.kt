package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.PlacementUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.WriteNoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.LibraryAction
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.LibraryIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.LibraryModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.LibraryPhase
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.LibraryState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.OwnerGroupUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library.SurfaceRowUi
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_library_disabled
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_library_empty
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_library_empty_hint
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_library_failed
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_library_title
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_loading
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_owner_off
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_owner_unknown
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_placement_card
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_placement_composer
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_placement_modal
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_placement_panel
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_placement_start
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_retry
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_show_surface
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** The surface library of the profile: owners with their surfaces, each with a switch and an editor. */
@Composable
internal fun LibraryScreen(
    model: LibraryModel,
    onOpen: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val open by rememberUpdatedState(onOpen)
    val state by produceState(LibraryState(), model) {
        model.store.collect {
            launch { actions.collect { action -> if (action is LibraryAction.OpenSurface) open(action.id) } }
            states.collect { value = it }
        }
    }
    LibraryContent(state, model.store::intent, onBack, modifier)
}

/** Stateless library screen. */
@Composable
internal fun LibraryContent(
    state: LibraryState,
    onIntent: (LibraryIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ServiceUiPane(stringResource(Res.string.service_ui_library_title), "service-ui-library", onBack, modifier) {
        state.notice?.let { notice ->
            item(key = "notice") { WriteNoticeBanner(notice, { onIntent(LibraryIntent.DismissNotice) }) }
        }
        if (!phaseItems(state.phase) { onIntent(LibraryIntent.Reload) }) return@ServiceUiPane
        if (state.groups.isEmpty()) {
            item(key = "empty") {
                HbEmptyState(
                    stringResource(Res.string.service_ui_library_empty),
                    Modifier.testTag("service-ui-library-empty"),
                    description = stringResource(Res.string.service_ui_library_empty_hint),
                )
            }
        }
        state.groups.forEach { group ->
            item(key = group.key) { OwnerSection(group, state.pending, onIntent) }
        }
    }
}

private fun LazyListScope.phaseItems(phase: LibraryPhase, onRetry: () -> Unit): Boolean {
    when (phase) {
        LibraryPhase.Ready -> return true

        LibraryPhase.Loading -> item(key = "loading") { HbLoadingState(stringResource(Res.string.service_ui_loading)) }

        LibraryPhase.Disabled -> item(key = "disabled") {
            HbEmptyState(
                stringResource(Res.string.service_ui_library_disabled),
                Modifier.testTag("service-ui-library-disabled"),
            )
        }

        LibraryPhase.Failed -> item(key = "failed") {
            HbEmptyState(
                stringResource(Res.string.service_ui_library_failed),
                Modifier.testTag("service-ui-library-failed"),
                action = {
                    HbButton(
                        stringResource(Res.string.service_ui_retry),
                        onRetry,
                        Modifier.testTag("service-ui-library-retry"),
                        style = HbButtonStyle.Secondary,
                        size = HbButtonSize.Small,
                    )
                },
            )
        }
    }
    return false
}

@Composable
private fun OwnerSection(
    group: OwnerGroupUi,
    pending: ImmutableSet<String>,
    onIntent: (LibraryIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hint = when {
        !group.isKnown -> stringResource(Res.string.service_ui_owner_unknown)
        !group.isEnabled -> stringResource(Res.string.service_ui_owner_off)
        else -> null
    }
    // One item per owner: the library holds at most a few dozen surfaces, so rows need not be lazy.
    HbSettingsSection(group.title, modifier.testTag("service-ui-owner-${group.key}"), description = hint) {
        group.surfaces.forEachIndexed { index, surface ->
            key(surface.id) {
                if (index > 0) HbDivider()
                SurfaceRow(surface, isPending = surface.id in pending, onIntent)
            }
        }
    }
}

@Composable
private fun SurfaceRow(
    surface: SurfaceRowUi,
    isPending: Boolean,
    onIntent: (LibraryIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placement = stringResource(surface.placement.title())
    HbSettingsRow(
        surface.title,
        modifier.fillMaxWidth().testTag("service-ui-surface-${surface.id}"),
        description = remember(placement, surface.description) {
            listOf(placement, surface.description).filter(String::isNotBlank).joinToString(" · ")
        },
        onClick = { onIntent(LibraryIntent.Open(surface.id)) },
    ) {
        HbSwitch(
            surface.isEnabled,
            { onIntent(LibraryIntent.SetEnabled(surface.id, it)) },
            stringResource(Res.string.service_ui_show_surface, surface.title),
            Modifier.testTag("service-ui-enabled-${surface.id}"),
            enabled = !isPending,
        )
    }
}

/** Where a surface of this placement appears. */
internal fun PlacementUi.title(): StringResource = when (this) {
    PlacementUi.Composer -> Res.string.service_ui_placement_composer
    PlacementUi.Start -> Res.string.service_ui_placement_start
    PlacementUi.Card -> Res.string.service_ui_placement_card
    PlacementUi.Panel -> Res.string.service_ui_placement_panel
    PlacementUi.Modal -> Res.string.service_ui_placement_modal
}

private val previewState = LibraryState(
    phase = LibraryPhase.Ready,
    groups = persistentListOf(
        OwnerGroupUi(
            key = "harness:h_1",
            title = "PR review",
            isKnown = true,
            isEnabled = true,
            surfaces = persistentListOf(
                SurfaceRowUi("harness:h_1:map", "PR map", "Dependencies and priorities", PlacementUi.Panel, true),
                SurfaceRowUi("harness:h_1:quick", "Quick actions", "", PlacementUi.Composer, false),
            ),
        ),
        OwnerGroupUi(
            key = "harness:h_2",
            title = "Release",
            isKnown = true,
            isEnabled = false,
            surfaces = persistentListOf(
                SurfaceRowUi("harness:h_2:notes", "Release notes", "", PlacementUi.Modal, true),
            ),
        ),
    ),
    pending = persistentSetOf("harness:h_1:quick"),
)

@Preview
@Composable
private fun LibraryLightPreview() {
    HbTheme(darkTheme = false) { LibraryContent(previewState, {}, null) }
}

@Preview
@Composable
private fun LibraryDarkPreview() {
    HbTheme(darkTheme = true) { LibraryContent(previewState.copy(notice = WriteNoticeUi.Changed), {}, {}) }
}

@Preview
@Composable
private fun LibraryEmptyPreview() {
    HbTheme(darkTheme = false) { LibraryContent(LibraryState(phase = LibraryPhase.Ready), {}, null) }
}
