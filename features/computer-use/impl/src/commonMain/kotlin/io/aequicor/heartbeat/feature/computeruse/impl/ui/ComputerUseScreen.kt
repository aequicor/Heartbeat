package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.BlockerUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseModel
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenIntent
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenState
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
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_save_failed
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Settings expose only the tool switch; the agent chooses its capture target, mode and frame quality. */
@Composable
internal fun ComputerUseScreen(model: ComputerUseModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(ComputerUseScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    ComputerUseContent(state, model.store::intent, onBack, modifier)
}

/** One settings row with a persistent tool switch and contextual operating-system permission help. */
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
                    HbSettingsSection(stringResource(Res.string.computer_use_title)) {
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
                if (state.hasError) {
                    item(key = "error") {
                        HbBanner(
                            stringResource(Res.string.computer_use_save_failed),
                            Modifier.testTag("computer-use-error"),
                        )
                    }
                }
                if (state.isEnabled && state.blockers.isNotEmpty()) {
                    item(key = "permissions") {
                        HbText(
                            state.blockers.map { stringResource(it.resource()) }.joinToString(separator = "\n"),
                            modifier = Modifier.testTag("computer-use-permissions"),
                            style = HbTheme.typography.caption,
                            color = HbTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

private fun BlockerUi.resource(): StringResource = when (this) {
    BlockerUi.UnsupportedPlatform -> Res.string.computer_use_blocker_platform
    BlockerUi.ScreenRecordingPermission -> Res.string.computer_use_blocker_screen_recording
    BlockerUi.AccessibilityPermission -> Res.string.computer_use_blocker_accessibility
    BlockerUi.ElevationRequired -> Res.string.computer_use_blocker_elevation
    BlockerUi.SessionLocked -> Res.string.computer_use_blocker_session_locked
    BlockerUi.Headless -> Res.string.computer_use_blocker_headless
}

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
