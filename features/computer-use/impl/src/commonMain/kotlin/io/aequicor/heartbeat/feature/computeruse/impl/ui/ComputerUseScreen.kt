package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.BlockerUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseModel
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenIntent
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenState
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.SettingsError
import io.aequicor.heartbeat.feature.computeruse.impl.resources.Res
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_back
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_accessibility
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_elevation
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_headless
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_platform
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_screen_recording
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_session_locked
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_enabled
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_enabled_hint
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_load_failed
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_permission_accessibility
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_permission_open
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_permission_open_description
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_permission_screen_recording
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_permissions_hint
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_save_failed
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_section_access
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_section_permissions
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_title
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Settings expose the tool switch and missing permissions; capture choices belong to the agent. */
@Composable
internal fun ComputerUseScreen(model: ComputerUseModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(ComputerUseScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    ComputerUseContent(state, model.store::intent, onBack, modifier)
}

/**
 * One settings row with a persistent tool switch. While the tool is on, each missing permission the user grants in
 * the system settings gets a row with a button that opens its page; other host blockers follow as a warning.
 * Permission rows and the warning are polite live regions, leaving shared instructions out of announcements.
 */
@Composable
internal fun ComputerUseContent(
    state: ComputerUseScreenState,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("computer-use"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                stringResource(Res.string.computer_use_title),
                leadingInset = if (HbTheme.dimensions.isDesktop) {
                    HbTheme.spacing.m
                } else {
                    HbTheme.dimensions.titlebarLeadingInset
                },
                navigation = {
                    HbIconButton(
                        HbIcons.ArrowLeft,
                        stringResource(Res.string.computer_use_back),
                        onBack,
                        Modifier.testTag("computer-use-back"),
                    )
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize().testTag("computer-use-list"),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                item(key = "tool") {
                    HbSettingsSection(stringResource(Res.string.computer_use_section_access)) {
                        HbSettingsRow(
                            stringResource(Res.string.computer_use_enabled),
                            Modifier.fillMaxWidth().testTag("computer-use-enabled-row"),
                            description = stringResource(Res.string.computer_use_enabled_hint),
                        ) {
                            HbSwitch(
                                state.isEnabled,
                                { onIntent(ComputerUseScreenIntent.SetEnabled(it)) },
                                stringResource(Res.string.computer_use_enabled),
                                Modifier.testTag("computer-use-enabled"),
                                enabled = state.isLoaded,
                            )
                        }
                    }
                }
                // The switch's own failure sits right below it, before the host's permission hints.
                state.error?.let { error ->
                    item(key = "error") {
                        HbBanner(stringResource(error.resource()), Modifier.testTag("computer-use-error"))
                    }
                }
                if (state.isEnabled) blockerItems(state.blockers, onIntent)
            }
        }
    }
}

/** Grantable permissions as rows with buttons, other host blockers as one warning. */
private fun LazyListScope.blockerItems(
    blockers: ImmutableList<BlockerUi>,
    onIntent: (ComputerUseScreenIntent) -> Unit,
) {
    val grantable = blockers.filter { it.isGrantable }.toImmutableList()
    val help = blockers.filterNot { it.isGrantable }
    if (grantable.isNotEmpty()) {
        item(key = "permissions") { PermissionsSection(grantable, onIntent) }
    }
    if (help.isNotEmpty()) {
        item(key = "blockers") {
            HbBanner(
                help.map { stringResource(it.resource()) }.joinToString(separator = "\n"),
                Modifier.testTag("computer-use-blockers"),
                tone = HbTone.Warning,
            )
        }
    }
}

/** Missing permissions, each with the button that opens its system settings page and the drag guide. */
@Composable
private fun PermissionsSection(
    blockers: ImmutableList<BlockerUi>,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.computer_use_section_permissions),
        modifier.testTag("computer-use-permissions"),
        description = stringResource(Res.string.computer_use_permissions_hint),
    ) {
        blockers.forEach { blocker ->
            key(blocker) {
                val title = stringResource(blocker.title())
                val openDescription = stringResource(Res.string.computer_use_permission_open_description, title)
                HbSettingsRow(
                    title,
                    Modifier.fillMaxWidth().testTag("computer-use-permission-${blocker.name}")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    description = stringResource(blocker.resource()),
                ) {
                    HbButton(
                        stringResource(Res.string.computer_use_permission_open),
                        { onIntent(ComputerUseScreenIntent.GrantPermission(blocker)) },
                        Modifier.testTag("computer-use-grant-${blocker.name}")
                            .semantics { contentDescription = openDescription },
                        style = HbButtonStyle.Secondary,
                        size = HbButtonSize.Small,
                    )
                }
            }
        }
    }
}

/** Permissions use their system settings page names; other blockers use their localized explanation. */
private fun BlockerUi.title(): StringResource = when (this) {
    BlockerUi.ScreenRecordingPermission -> Res.string.computer_use_permission_screen_recording

    BlockerUi.AccessibilityPermission -> Res.string.computer_use_permission_accessibility

    BlockerUi.UnsupportedPlatform, BlockerUi.ElevationRequired, BlockerUi.SessionLocked, BlockerUi.Headless ->
        resource()
}

private fun BlockerUi.resource(): StringResource = when (this) {
    BlockerUi.UnsupportedPlatform -> Res.string.computer_use_blocker_platform
    BlockerUi.ScreenRecordingPermission -> Res.string.computer_use_blocker_screen_recording
    BlockerUi.AccessibilityPermission -> Res.string.computer_use_blocker_accessibility
    BlockerUi.ElevationRequired -> Res.string.computer_use_blocker_elevation
    BlockerUi.SessionLocked -> Res.string.computer_use_blocker_session_locked
    BlockerUi.Headless -> Res.string.computer_use_blocker_headless
}

private fun SettingsError.resource(): StringResource = when (this) {
    SettingsError.LoadFailed -> Res.string.computer_use_load_failed
    SettingsError.SaveFailed -> Res.string.computer_use_save_failed
}

/** Enabled tool with missing operating-system permissions and a failed save, as the screen shows them together. */
private val attentionPreviewState = ComputerUseScreenState(
    isEnabled = true,
    isLoaded = true,
    blockers = persistentListOf(BlockerUi.ScreenRecordingPermission, BlockerUi.AccessibilityPermission),
    error = SettingsError.SaveFailed,
)

@Preview
@Composable
private fun ComputerUseLightPreview() {
    HbTheme(darkTheme = false) {
        ComputerUseContent(ComputerUseScreenState(isLoaded = true), {}, null)
    }
}

@Preview
@Composable
private fun ComputerUseDarkPreview() {
    HbTheme(darkTheme = true) {
        ComputerUseContent(ComputerUseScreenState(isEnabled = true, isLoaded = true), {}, {})
    }
}

@Preview
@Composable
private fun ComputerUseAttentionLightPreview() {
    HbTheme(darkTheme = false) {
        ComputerUseContent(attentionPreviewState, {}, null)
    }
}

@Preview
@Composable
private fun ComputerUseAttentionDarkPreview() {
    HbTheme(darkTheme = true) {
        ComputerUseContent(attentionPreviewState, {}, {})
    }
}
