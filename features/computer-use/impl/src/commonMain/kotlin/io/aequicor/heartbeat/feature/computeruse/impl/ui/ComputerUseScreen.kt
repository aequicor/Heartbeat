package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbSwitch
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.BlockerUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ButtonUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseModel
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenIntent
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseScreenState
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.FrameUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.JournalEntryUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.JournalKindUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ModeUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.PanelMessage
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.PhaseUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.RejectionUi
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.WindowRowUi
import io.aequicor.heartbeat.feature.computeruse.impl.resources.Res
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_action_click
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_action_drag
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_action_key
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_action_move
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_action_scroll
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_action_type
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_back
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_accessibility
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_elevation
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_headless
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_platform
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_screen_recording
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_blocker_session_locked
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_button_left
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_button_middle
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_button_right
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_capture_frame
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_dismiss
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_frame
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_frame_count
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_frame_empty
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_frame_info_details
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_input
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_input_hint
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_intro
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_journal
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_journal_empty
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_message_blocked
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_message_frame_expired
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_message_not_armed
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_message_other
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_message_outside
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_message_revoked
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_message_target_closed
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_message_too_large
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_message_unavailable
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_mode
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_mode_desktop
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_mode_window
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_preset
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_preset_detail
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_preset_overview
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_preset_text
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_preset_ui
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_refresh
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_revoke
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_start
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_status_blocked
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_status_capturing
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_status_checking
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_status_failed
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_status_idle
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_status_ready
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_stop
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_title
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_window_minimized
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_windows
import io.aequicor.heartbeat.feature.computeruse.impl.resources.computer_use_windows_empty
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** The computer use control panel: mode, window picker, frame preview, input arming and the kill switch. */
@Composable
internal fun ComputerUseScreen(
    model: ComputerUseModel,
    onBack: (() -> Unit)?,
    decodeFrame: suspend (ByteArray) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val state by produceState(ComputerUseScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    ComputerUseContent(state, model.store::intent, onBack, modifier, decodeFrame)
}

/**
 * Panel content.
 *
 * The status line states what the host may do; the mode section starts or switches a capture; the window list
 * feeds window mode; the frame section shows the last stored frame with its size and token estimate; the input
 * section arms injection. The fixed footer holds the kill switch, which stops capture and deletes its frames.
 */
@Composable
internal fun ComputerUseContent(
    state: ComputerUseScreenState,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    decodeFrame: suspend (ByteArray) -> ImageBitmap? = { null },
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
                item(key = "intro") { PanelIntro(state, onIntent) }
                item(key = "message") { PanelMessage(state.message, onIntent) }
                item(key = "mode") { ModeSection(state, onIntent) }
                if (state.mode == ModeUi.Window) {
                    item(key = "windows") { WindowSection(state, onIntent) }
                }
                item(key = "frame") { FrameSection(state, onIntent, decodeFrame) }
                item(key = "input") { InputSection(state, onIntent) }
                item(key = "journal") { JournalSection(state) }
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(HbTheme.spacing.m),
            contentAlignment = Alignment.Center,
        ) {
            HbButton(
                stringResource(Res.string.computer_use_revoke),
                { onIntent(ComputerUseScreenIntent.Revoke) },
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxWidth()
                    .testTag("computer-use-revoke"),
                HbButtonStyle.Danger,
                size = HbButtonSize.Small,
            )
        }
    }
}

@Composable
private fun PanelIntro(
    state: ComputerUseScreenState,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        HbText(
            stringResource(Res.string.computer_use_intro),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbText(
            stringResource(state.phase.statusResource()),
            style = HbTheme.typography.body,
            modifier = Modifier.testTag("computer-use-status"),
        )
        if (state.phase == PhaseUi.Checking) {
            HbLoadingState(stringResource(Res.string.computer_use_status_checking))
        }
        if (state.phase == PhaseUi.Idle || state.phase == PhaseUi.Blocked || state.phase == PhaseUi.Failed) {
            HbButton(
                stringResource(Res.string.computer_use_refresh),
                { onIntent(ComputerUseScreenIntent.Retry) },
                Modifier.testTag("computer-use-retry"),
                HbButtonStyle.Secondary,
            )
        }
    }
}

@Composable
private fun PanelMessage(
    message: PanelMessage?,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    HbBanner(message.text(), modifier.testTag("computer-use-message")) {
        HbButton(
            stringResource(Res.string.computer_use_dismiss),
            { onIntent(ComputerUseScreenIntent.DismissMessage) },
            style = HbButtonStyle.Ghost,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun ModeSection(
    state: ComputerUseScreenState,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCapturing = state.phase == PhaseUi.Capturing
    HbSettingsSection(stringResource(Res.string.computer_use_mode), modifier) {
        HbFlowRow(Modifier.fillMaxWidth().padding(HbTheme.spacing.m), gap = HbTheme.spacing.xs) {
            ModeButton(ModeUi.Desktop, state, onIntent)
            ModeButton(ModeUi.Window, state, onIntent)
        }
        HbDivider()
        HbSettingsRow(
            stringResource(if (isCapturing) Res.string.computer_use_stop else Res.string.computer_use_start),
            Modifier.testTag("computer-use-toggle-capture"),
            description = stringResource(Res.string.computer_use_frame_count, state.frameCount),
        ) {
            if (isCapturing) {
                HbButton(
                    stringResource(Res.string.computer_use_stop),
                    { onIntent(ComputerUseScreenIntent.StopCapture) },
                    Modifier.testTag("computer-use-stop"),
                    HbButtonStyle.Secondary,
                    size = HbButtonSize.Small,
                )
            } else {
                HbButton(
                    stringResource(Res.string.computer_use_start),
                    { onIntent(ComputerUseScreenIntent.StartCapture) },
                    Modifier.testTag("computer-use-start"),
                    enabled = state.phase == PhaseUi.Ready,
                    size = HbButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun ModeButton(
    mode: ModeUi,
    state: ComputerUseScreenState,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(
        if (mode == ModeUi.Desktop) Res.string.computer_use_mode_desktop else Res.string.computer_use_mode_window,
    )
    HbButton(
        label,
        { onIntent(ComputerUseScreenIntent.SelectMode(mode)) },
        modifier.testTag("computer-use-mode-$mode").semantics { selected = state.mode == mode },
        style = if (state.mode == mode) HbButtonStyle.Primary else HbButtonStyle.Ghost,
        enabled = mode == ModeUi.Desktop || state.isWindowModeAvailable,
        size = HbButtonSize.Small,
    )
}

@Composable
private fun WindowSection(
    state: ComputerUseScreenState,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(stringResource(Res.string.computer_use_windows), modifier) {
        HbSettingsRow(
            stringResource(Res.string.computer_use_refresh),
            Modifier.testTag("computer-use-refresh-windows"),
        ) {
            HbButton(
                stringResource(Res.string.computer_use_refresh),
                { onIntent(ComputerUseScreenIntent.Refresh) },
                style = HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }
        if (state.targets.isEmpty()) {
            HbEmptyState(stringResource(Res.string.computer_use_windows_empty))
        }
        state.targets.forEachIndexed { index, target ->
            if (index > 0) HbDivider()
            WindowRow(target, target.id == state.selectedWindowId, onIntent)
        }
    }
}

@Composable
private fun WindowRow(
    target: WindowRowUi,
    isSelected: Boolean,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minimized = if (target.isMinimized) stringResource(Res.string.computer_use_window_minimized) else target.area
    HbSettingsRow(
        target.title,
        modifier.fillMaxWidth().testTag("computer-use-window-${target.id}"),
        description = "$minimized · ${target.application}",
    ) {
        HbButton(
            stringResource(Res.string.computer_use_mode_window),
            { onIntent(ComputerUseScreenIntent.SelectWindow(target.id)) },
            Modifier.testTag("computer-use-select-${target.id}").semantics { selected = isSelected },
            style = if (isSelected) HbButtonStyle.Primary else HbButtonStyle.Ghost,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun FrameSection(
    state: ComputerUseScreenState,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    decodeFrame: suspend (ByteArray) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(
        stringResource(Res.string.computer_use_frame),
        modifier,
        description = stringResource(Res.string.computer_use_preset),
    ) {
        HbFlowRow(Modifier.fillMaxWidth().padding(HbTheme.spacing.m), gap = HbTheme.spacing.xs) {
            PresetButton("overview", Res.string.computer_use_preset_overview, state, onIntent)
            PresetButton("text", Res.string.computer_use_preset_text, state, onIntent)
            PresetButton("detail", Res.string.computer_use_preset_detail, state, onIntent)
            PresetButton("ui", Res.string.computer_use_preset_ui, state, onIntent)
        }
        HbDivider()
        HbSettingsRow(
            stringResource(Res.string.computer_use_capture_frame),
            Modifier.testTag("computer-use-capture-row"),
            description = state.frame?.let { frameDescription(it) }
                ?: stringResource(Res.string.computer_use_frame_empty),
        ) {
            HbButton(
                stringResource(Res.string.computer_use_capture_frame),
                { onIntent(ComputerUseScreenIntent.CaptureFrame) },
                Modifier.testTag("computer-use-capture"),
                enabled = state.phase == PhaseUi.Capturing && state.isCaptureOpen,
                size = HbButtonSize.Small,
            )
        }
        FramePreview(state.frame, decodeFrame)
    }
}

@Composable
private fun PresetButton(
    name: String,
    label: StringResource,
    state: ComputerUseScreenState,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbButton(
        stringResource(label),
        { onIntent(ComputerUseScreenIntent.SelectPreset(name)) },
        modifier.testTag("computer-use-preset-$name").semantics { selected = state.preset == name },
        style = if (state.preset == name) HbButtonStyle.Primary else HbButtonStyle.Ghost,
        size = HbButtonSize.Small,
    )
}

@Composable
private fun FramePreview(
    frame: FrameUi?,
    decodeFrame: suspend (ByteArray) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val decode by rememberUpdatedState(decodeFrame)
    val decoded by produceState<DecodedPreview?>(null, frame?.id, frame?.session, frame?.content) {
        value = null
        value = frame?.content?.let { DecodedPreview(frame.id, frame.session, decode(it)) }
    }
    // A changed key cancels decoding, and hides the previous bitmap before the new producer runs.
    val bitmap = decoded?.takeIf { frame != null && it.id == frame.id && it.session == frame.session }?.bitmap
    Box(
        modifier.fillMaxWidth().aspectRatio(PREVIEW_ASPECT_RATIO).padding(HbTheme.spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            HbText(
                stringResource(Res.string.computer_use_frame_empty),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
            )
        } else {
            Image(
                bitmap,
                contentDescription = stringResource(Res.string.computer_use_frame),
                modifier = Modifier.fillMaxSize().testTag("computer-use-preview"),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun InputSection(
    state: ComputerUseScreenState,
    onIntent: (ComputerUseScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbSettingsSection(stringResource(Res.string.computer_use_input), modifier) {
        HbSettingsRow(
            stringResource(Res.string.computer_use_input),
            Modifier.testTag("computer-use-arm-row"),
            description = stringResource(Res.string.computer_use_input_hint),
        ) {
            HbSwitch(
                state.isInputArmed,
                { onIntent(ComputerUseScreenIntent.ArmInput(it)) },
                stringResource(Res.string.computer_use_input),
                Modifier.testTag("computer-use-arm"),
                enabled = state.isInputAvailable && state.phase == PhaseUi.Capturing && state.isCaptureOpen,
            )
        }
    }
}

@Composable
private fun JournalSection(state: ComputerUseScreenState, modifier: Modifier = Modifier) {
    HbSettingsSection(stringResource(Res.string.computer_use_journal), modifier) {
        if (state.journal.isEmpty()) {
            HbEmptyState(stringResource(Res.string.computer_use_journal_empty))
        }
        state.journal.forEach { entry ->
            HbText(
                entry.text(),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(HbTheme.spacing.m),
            )
        }
    }
}

@Composable
private fun frameDescription(frame: FrameUi): String = stringResource(
    Res.string.computer_use_frame_info_details,
    frame.widthPx,
    frame.heightPx,
    frame.format,
    frame.bytes / BYTES_PER_KIB,
    frame.estimatedTokens,
)

@Composable
private fun PanelMessage.text(): String = when (this) {
    is PanelMessage.Rejected -> stringResource(
        when (reason) {
            RejectionUi.NotArmed -> Res.string.computer_use_message_not_armed
            RejectionUi.OutsideCapture -> Res.string.computer_use_message_outside
            RejectionUi.TargetClosed -> Res.string.computer_use_message_target_closed
            RejectionUi.FrameExpired -> Res.string.computer_use_message_frame_expired
            RejectionUi.TooLarge -> Res.string.computer_use_message_too_large
            RejectionUi.Unavailable -> Res.string.computer_use_message_unavailable
            RejectionUi.Other -> Res.string.computer_use_message_other
        },
    )

    is PanelMessage.Blocked -> stringResource(
        Res.string.computer_use_message_blocked,
        blockers.map { stringResource(it.resource()) }.joinToString(),
    )

    PanelMessage.Revoked -> stringResource(Res.string.computer_use_message_revoked)
}

private fun PhaseUi.statusResource() = when (this) {
    PhaseUi.Idle -> Res.string.computer_use_status_idle
    PhaseUi.Checking -> Res.string.computer_use_status_checking
    PhaseUi.Blocked -> Res.string.computer_use_status_blocked
    PhaseUi.Ready -> Res.string.computer_use_status_ready
    PhaseUi.Capturing -> Res.string.computer_use_status_capturing
    PhaseUi.Failed -> Res.string.computer_use_status_failed
}

private fun BlockerUi.resource(): StringResource = when (this) {
    BlockerUi.UnsupportedPlatform -> Res.string.computer_use_blocker_platform
    BlockerUi.ScreenRecordingPermission -> Res.string.computer_use_blocker_screen_recording
    BlockerUi.AccessibilityPermission -> Res.string.computer_use_blocker_accessibility
    BlockerUi.ElevationRequired -> Res.string.computer_use_blocker_elevation
    BlockerUi.SessionLocked -> Res.string.computer_use_blocker_session_locked
    BlockerUi.Headless -> Res.string.computer_use_blocker_headless
}

@Composable
private fun JournalEntryUi.text(): String = when (kind) {
    JournalKindUi.Move -> stringResource(Res.string.computer_use_action_move)
    JournalKindUi.Click -> stringResource(Res.string.computer_use_action_click, button.text(), count)
    JournalKindUi.Drag -> stringResource(Res.string.computer_use_action_drag, button.text())
    JournalKindUi.Scroll -> stringResource(Res.string.computer_use_action_scroll, deltaX, deltaY)
    JournalKindUi.Type -> stringResource(Res.string.computer_use_action_type, count)
    JournalKindUi.Key -> stringResource(Res.string.computer_use_action_key, count)
}

@Composable
private fun ButtonUi.text(): String = stringResource(
    when (this) {
        ButtonUi.Left -> Res.string.computer_use_button_left
        ButtonUi.Right -> Res.string.computer_use_button_right
        ButtonUi.Middle -> Res.string.computer_use_button_middle
    },
)

private const val BYTES_PER_KIB = 1024L
private const val PREVIEW_ASPECT_RATIO = 16f / 9f

private data class DecodedPreview(val id: String, val session: String, val bitmap: ImageBitmap?)

@Preview
@Composable
private fun ComputerUseLightPreview() {
    HbTheme(darkTheme = false) {
        ComputerUseContent(ComputerUseScreenState(phase = PhaseUi.Ready), {}, null)
    }
}

@Preview
@Composable
private fun ComputerUseDarkPreview() {
    HbTheme(darkTheme = true) {
        ComputerUseContent(ComputerUseScreenState(phase = PhaseUi.Capturing), {}, {})
    }
}
