package io.aequicor.heartbeat.feature.computeruse.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CapturePresets
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.MouseButton
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.uuid.Uuid

private typealias PanelPipeline =
    PipelineContext<ComputerUseScreenState, ComputerUseScreenIntent, ComputerUseScreenAction>

/** Lifecycle phase of the feature, mirrored from the machine. */
internal enum class PhaseUi { Idle, Checking, Blocked, Ready, Capturing, Failed }

/** Capture mode the panel offers. */
internal enum class ModeUi { Desktop, Window }

/** Why the panel shows a warning; the screen maps it to a localized string. */
internal enum class RejectionUi { NotArmed, OutsideCapture, TargetClosed, FrameExpired, TooLarge, Unavailable, Other }

/** Host blockers presented by localized UI messages. */
internal enum class BlockerUi {
    UnsupportedPlatform,
    ScreenRecordingPermission,
    AccessibilityPermission,
    ElevationRequired,
    SessionLocked,
    Headless,
}

/** Mouse button name shown in the action journal. */
internal enum class ButtonUi { Left, Right, Middle }

/** One panel message. */
internal sealed interface PanelMessage {
    /** A request was refused. */
    data class Rejected(val reason: RejectionUi) : PanelMessage

    /** The operating system refuses capture or input. */
    data class Blocked(val blockers: ImmutableList<BlockerUi>) : PanelMessage

    /** The kill switch revoked capture and input; frame cleanup continues separately. */
    data object Revoked : PanelMessage
}

/** One window row; the title is shown but never logged. */
internal data class WindowRowUi(
    val id: String,
    val application: String,
    val title: String,
    val area: String,
    val isMinimized: Boolean,
)

/** The stored frame the panel previews. Encoded content is user data and is never logged. */
internal data class FrameUi(
    val id: String,
    val session: String,
    val widthPx: Int,
    val heightPx: Int,
    val masterWidthPx: Int,
    val masterHeightPx: Int,
    val bytes: Long,
    val estimatedTokens: Int,
    val format: String,
    val content: ByteArray?,
) {
    override fun toString(): String = "FrameUi(id=$id, ${widthPx}x$heightPx, bytes=$bytes)"
}

/** Content-free input action details; the screen localizes these instead of storing rendered strings. */
internal data class JournalEntryUi(
    val kind: JournalKindUi,
    val button: ButtonUi = ButtonUi.Left,
    val count: Int = 0,
    val deltaX: Int = 0,
    val deltaY: Int = 0,
)

/** Actions the journal renders. */
internal enum class JournalKindUi { Move, Click, Drag, Scroll, Type, Key }

/** Immutable presentation state of the panel. */
@Immutable
internal data class ComputerUseScreenState(
    val phase: PhaseUi = PhaseUi.Idle,
    val mode: ModeUi = ModeUi.Desktop,
    val targets: ImmutableList<WindowRowUi> = persistentListOf(),
    val selectedWindowId: String? = null,
    val isInputArmed: Boolean = false,
    val isInputAvailable: Boolean = false,
    val isCaptureOpen: Boolean = false,
    val isWindowModeAvailable: Boolean = false,
    val preset: String = DEFAULT_PRESET,
    val frame: FrameUi? = null,
    val captureSession: String? = null,
    val journal: ImmutableList<JournalEntryUi> = persistentListOf(),
    val message: PanelMessage? = null,
    val frameCount: Long = 0L,
) : MVIState

/** User events of the panel. */
internal sealed interface ComputerUseScreenIntent : MVIIntent {
    /** Chooses what to capture. */
    data class SelectMode(val mode: ModeUi) : ComputerUseScreenIntent

    /** Chooses the window to capture. */
    data class SelectWindow(val id: String) : ComputerUseScreenIntent

    /** Re-reads the window list. */
    data object Refresh : ComputerUseScreenIntent

    /** Starts or switches the capture of the selected mode. */
    data object StartCapture : ComputerUseScreenIntent

    /** Captures one more frame with the selected preset. */
    data object CaptureFrame : ComputerUseScreenIntent

    /** Ends the capture and deletes its frames. */
    data object StopCapture : ComputerUseScreenIntent

    /** Arms or disarms mouse and keyboard injection. */
    data class ArmInput(val isArmed: Boolean) : ComputerUseScreenIntent

    /** Chooses the frame preset saved in the profile. */
    data class SelectPreset(val name: String) : ComputerUseScreenIntent

    /** The saved profile preset arrived. */
    data class PresetLoaded(val name: String) : ComputerUseScreenIntent

    /** Kill switch: stops everything immediately. */
    data object Revoke : ComputerUseScreenIntent

    /** Probes availability again. */
    data object Retry : ComputerUseScreenIntent

    /** Hides the current message. */
    data object DismissMessage : ComputerUseScreenIntent
}

/** Reserved contract for one-off screen actions. */
internal sealed interface ComputerUseScreenAction : MVIAction

/**
 * Screen store of the control panel.
 *
 * It mirrors the profile machine and forwards user events as intents; every decision — whether input is
 * allowed, whether a mode is supported — stays in the machine, and a refusal arrives as a message.
 */
internal class ComputerUseModel(
    private val machine: Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>,
    frames: FrameStore,
    private val preferences: ComputerUsePreferences,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    private val log = Log.tag("ComputerUseModel")

    /** The panel store. */
    val store = factory.create<ComputerUseScreenState, ComputerUseScreenIntent, ComputerUseScreenAction>(
        "ComputerUse",
        ComputerUseScreenState().reflectState(machine.state.value),
        onError = { copy(message = PanelMessage.Rejected(RejectionUi.Other)) },
    ) {
        reflect(machine, onOutput = { output -> onOutput(output) }) { reflectState(it) }
        whileSubscribed {
            machine.state.map { it.preview() }.distinctUntilChanged().collectLatest { capture ->
                if (capture != null) {
                    val content = try {
                        frames.read(capture.path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.w(e) { "frame preview read failed" }
                        null
                    }
                    updateState {
                        if (machine.state.value.preview() == capture) {
                            copy(frame = capture.toUi(content))
                        } else {
                            this
                        }
                    }
                }
            }
        }
        reduce { intent -> onIntent(intent) }
    }

    init {
        store.start(scope)
        scope.launch {
            store.intent(ComputerUseScreenIntent.PresetLoaded(preferences.read().preset))
            machine.send(ComputerUseIntent.Public.Start)
        }
    }

    // FlowMVI's PipelineContext is the receiver required by store operations and also implements CoroutineScope.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelPipeline.onOutput(output: ComputerUseOutput) {
        when (output) {
            is ComputerUseOutput.FrameReady -> {
                // The current state supplies the frame, including when an output was missed while closed.
                updateState { copy(message = null) }
            }

            is ComputerUseOutput.Rejected ->
                updateState { copy(message = PanelMessage.Rejected(output.reason.rejection())) }

            is ComputerUseOutput.PermissionRequired -> updateState {
                copy(message = PanelMessage.Blocked(output.blockers.map { it.toUi() }.toImmutableList()))
            }

            is ComputerUseOutput.InputApplied ->
                updateState { copy(journal = journal.with(output.action.entry())) }

            // State reflection invalidates the previous frame or session.
            is ComputerUseOutput.CaptureChanged -> Unit

            is ComputerUseOutput.SessionClosed -> Unit

            ComputerUseOutput.Revoked -> updateState {
                copy(frame = null, journal = persistentListOf(), message = PanelMessage.Revoked)
            }
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (itself a CoroutineScope); store DSL functions extend it.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelPipeline.onIntent(intent: ComputerUseScreenIntent) {
        when (intent) {
            is ComputerUseScreenIntent.SelectMode -> selectMode(intent.mode)

            is ComputerUseScreenIntent.SelectWindow -> selectWindow(intent.id)

            ComputerUseScreenIntent.Refresh -> sendTo(machine, ComputerUseIntent.Public.RefreshTargets)

            ComputerUseScreenIntent.StartCapture -> startCapture()

            ComputerUseScreenIntent.CaptureFrame -> captureFrame()

            ComputerUseScreenIntent.StopCapture -> sendTo(machine, ComputerUseIntent.Public.EndCapture)

            is ComputerUseScreenIntent.ArmInput ->
                sendTo(machine, ComputerUseIntent.Public.ArmInput(intent.isArmed))

            is ComputerUseScreenIntent.SelectPreset -> selectPreset(intent.name)

            is ComputerUseScreenIntent.PresetLoaded -> updateState { copy(preset = intent.name) }

            ComputerUseScreenIntent.Revoke -> sendTo(machine, ComputerUseIntent.Public.Revoke)

            ComputerUseScreenIntent.Retry -> retry()

            ComputerUseScreenIntent.DismissMessage -> updateState { copy(message = null) }
        }
    }

    // Store updates and sendTo extend FlowMVI's coroutine-backed PipelineContext.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelPipeline.selectMode(mode: ModeUi) {
        updateState {
            val selected = selectedWindowId?.takeIf { id -> targets.any { it.id == id } }
                ?: targets.firstOrNull { !it.isMinimized }?.id
            copy(mode = mode, selectedWindowId = selected, message = null)
        }
        val current = machine.state.value as? ComputerUseState.Capturing ?: return
        val activeMode = if (current.mode is ComputerUseMode.Window) ModeUi.Window else ModeUi.Desktop
        if (activeMode != mode) startCapture()
    }

    // Store updates and sendTo extend FlowMVI's coroutine-backed PipelineContext.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelPipeline.selectWindow(id: String) {
        updateState { copy(selectedWindowId = id, message = null) }
        val current = machine.state.value as? ComputerUseState.Capturing ?: return
        if ((current.mode as? ComputerUseMode.Window)?.target?.id?.value != id) startCapture()
    }

    // sendTo extends FlowMVI's coroutine-backed PipelineContext.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelPipeline.retry() {
        sendTo(
            machine,
            if (machine.state.value == ComputerUseState.Idle) {
                ComputerUseIntent.Public.Start
            } else {
                ComputerUseIntent.Public.Retry
            },
        )
    }

    // Store updates and sendTo extend FlowMVI's coroutine-backed PipelineContext.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelPipeline.startCapture() {
        var isDesktop = true
        var selectedId: String? = null
        withState {
            isDesktop = mode == ModeUi.Desktop
            selectedId = selectedWindowId
        }
        val mode = if (isDesktop) ComputerUseMode.Desktop() else windowMode(selectedId)
        if (mode == null) {
            log.w { "capture start refused: no window is selected" }
            updateState { copy(message = PanelMessage.Rejected(RejectionUi.Other)) }
            return
        }
        val session = CaptureSessionId(Uuid.random().toString())
        val intent = when (val current = machine.state.value) {
            is ComputerUseState.Capturing -> ComputerUseIntent.Public.SwitchMode(
                mode,
                session,
                owner = CaptureOwner.Panel,
                expectedSession = current.session,
            )

            ComputerUseState.Idle, ComputerUseState.Checking, is ComputerUseState.Ready,
            is ComputerUseState.Unavailable, is ComputerUseState.Failed,
            ->
                ComputerUseIntent.Public.BeginCapture(mode, CaptureOwner.Panel, session)
        }
        sendTo(machine, intent) {
            updateState { copy(message = PanelMessage.Rejected(RejectionUi.Other)) }
        }
    }

    /** The machine mode of a window selection; the real target comes from the machine state. */
    private fun windowMode(selectedId: String?): ComputerUseMode? {
        if (selectedId == null) return null
        val targets = when (val machineState = machine.state.value) {
            is ComputerUseState.Ready -> machineState.targets
            is ComputerUseState.Capturing -> machineState.targets
            ComputerUseState.Idle -> emptyList()
            ComputerUseState.Checking -> emptyList()
            is ComputerUseState.Unavailable -> emptyList()
            is ComputerUseState.Failed -> emptyList()
        }
        return targets.firstOrNull { it.id.value == selectedId }?.let { ComputerUseMode.Window(it) }
    }

    // Store reads and sendTo extend FlowMVI's coroutine-backed PipelineContext.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelPipeline.captureFrame() {
        var presetName = DEFAULT_PRESET
        withState { presetName = preset }
        val settings = preferences.read()
        val encoding = CapturePresets.byName(presetName) ?: settings.encoding
        sendTo(
            machine,
            ComputerUseIntent.Public.Capture(
                CaptureRequest(encoding = encoding, isCursorIncluded = settings.isCursorIncluded),
            ),
        )
    }

    // updateState extends FlowMVI's coroutine-backed PipelineContext.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelPipeline.selectPreset(name: String) {
        if (preferences.setPreset(name)) {
            updateState { copy(preset = name) }
        } else {
            updateState { copy(message = PanelMessage.Rejected(RejectionUi.Other)) }
        }
    }
}

private fun ComputerUseScreenState.withoutCapture(): ComputerUseScreenState = copy(
    frame = null,
    captureSession = null,
    journal = persistentListOf(),
    isInputArmed = false,
    isCaptureOpen = false,
    frameCount = 0L,
)

private fun ComputerUseScreenState.reflectState(state: ComputerUseState): ComputerUseScreenState = when (state) {
    ComputerUseState.Idle -> withoutCapture().copy(
        phase = PhaseUi.Idle,
        isInputAvailable = false,
        isWindowModeAvailable = false,
    )

    ComputerUseState.Checking -> withoutCapture().copy(phase = PhaseUi.Checking, message = null)

    is ComputerUseState.Unavailable -> withoutCapture().copy(
        phase = PhaseUi.Blocked,
        message = PanelMessage.Blocked(state.blockers.map { it.toUi() }.toImmutableList()),
        isInputAvailable = false,
        isWindowModeAvailable = false,
    )

    is ComputerUseState.Ready -> withoutCapture().copy(
        phase = PhaseUi.Ready,
        targets = state.targets.toRows(),
        isInputArmed = state.isInputArmed,
        isInputAvailable = state.capabilities.isInputAvailable,
        isWindowModeAvailable = state.capabilities.isWindowCaptureAvailable,
        frame = state.preview()?.toUi(frame),
        message = message.takeIf { phase == PhaseUi.Ready },
    )

    is ComputerUseState.Capturing -> copy(
        phase = PhaseUi.Capturing,
        mode = if (state.mode is ComputerUseMode.Window) ModeUi.Window else ModeUi.Desktop,
        selectedWindowId = (state.mode as? ComputerUseMode.Window)?.target?.id?.value ?: selectedWindowId,
        targets = state.targets.toRows(),
        isInputArmed = state.isInputArmed,
        isInputAvailable = state.capabilities.isInputAvailable,
        isWindowModeAvailable = state.capabilities.isWindowCaptureAvailable,
        frameCount = state.frameCount,
        isCaptureOpen = state.isOpen,
        frame = state.preview()?.toUi(frame),
        captureSession = state.session.value,
        journal = if (captureSession == state.session.value) journal else persistentListOf(),
        message = message.takeIf { captureSession == state.session.value },
    )

    is ComputerUseState.Failed -> withoutCapture().copy(
        phase = PhaseUi.Failed,
        message = PanelMessage.Rejected(state.reason.rejection()),
    )
}

private fun List<WindowTarget>.toRows(): ImmutableList<WindowRowUi> = map { target ->
    WindowRowUi(
        id = target.id.value,
        application = target.application,
        title = target.title,
        area = "${target.bounds.widthPx}x${target.bounds.heightPx}",
        isMinimized = target.isMinimized,
    )
}.toImmutableList()

private fun ComputerUseFailure.rejection(): RejectionUi = when (this) {
    ComputerUseFailure.NotArmed -> RejectionUi.NotArmed
    ComputerUseFailure.RegionOutOfBounds -> RejectionUi.OutsideCapture
    ComputerUseFailure.TargetClosed, ComputerUseFailure.TargetMinimized -> RejectionUi.TargetClosed
    ComputerUseFailure.CaptureExpired, ComputerUseFailure.UnknownCapture -> RejectionUi.FrameExpired
    ComputerUseFailure.CropTooLarge, ComputerUseFailure.EncodingTooLarge -> RejectionUi.TooLarge
    ComputerUseFailure.Unavailable, ComputerUseFailure.PermissionLost -> RejectionUi.Unavailable
    ComputerUseFailure.CaptureFailed -> RejectionUi.Other
    ComputerUseFailure.InputRejected -> RejectionUi.Other
    ComputerUseFailure.UnsupportedCharacter -> RejectionUi.Other
    ComputerUseFailure.ModeNotAllowed -> RejectionUi.Other
    ComputerUseFailure.Timeout -> RejectionUi.Other
}

private fun InputAction.entry(): JournalEntryUi = when (this) {
    is InputAction.MoveTo -> JournalEntryUi(JournalKindUi.Move)
    is InputAction.Click -> JournalEntryUi(JournalKindUi.Click, button = button.toUi(), count = count)
    is InputAction.Drag -> JournalEntryUi(JournalKindUi.Drag, button = button.toUi())
    is InputAction.Scroll -> JournalEntryUi(JournalKindUi.Scroll, deltaX = deltaX, deltaY = deltaY)
    is InputAction.Type -> JournalEntryUi(JournalKindUi.Type, count = text.length)
    is InputAction.Key -> JournalEntryUi(JournalKindUi.Key, count = keys.size)
}

private fun ComputerUseBlocker.toUi(): BlockerUi = when (this) {
    ComputerUseBlocker.UnsupportedPlatform -> BlockerUi.UnsupportedPlatform
    ComputerUseBlocker.ScreenRecordingPermission -> BlockerUi.ScreenRecordingPermission
    ComputerUseBlocker.AccessibilityPermission -> BlockerUi.AccessibilityPermission
    ComputerUseBlocker.ElevationRequired -> BlockerUi.ElevationRequired
    ComputerUseBlocker.SessionLocked -> BlockerUi.SessionLocked
    ComputerUseBlocker.Headless -> BlockerUi.Headless
}

private fun MouseButton.toUi(): ButtonUi = when (this) {
    MouseButton.Left -> ButtonUi.Left
    MouseButton.Right -> ButtonUi.Right
    MouseButton.Middle -> ButtonUi.Middle
}

private fun ImmutableList<JournalEntryUi>.with(entry: JournalEntryUi): ImmutableList<JournalEntryUi> =
    (this + entry).takeLast(JOURNAL_SIZE).toImmutableList()

/** The latest presentation artifact of the active machine state, including crops. */
private fun ComputerUseState.preview(): CaptureRef? = when (this) {
    is ComputerUseState.Capturing -> listOfNotNull(lastPreview, lastCrop).maxByOrNull { it.sequence }

    is ComputerUseState.Ready -> lastPreview

    ComputerUseState.Idle,
    ComputerUseState.Checking,
    is ComputerUseState.Unavailable,
    is ComputerUseState.Failed,
    -> null
}

private fun CaptureRef.toUi(previous: FrameUi?): FrameUi = toUi(
    previous?.content?.takeIf { previous.id == id.value && previous.session == session.value },
)

private fun CaptureRef.toUi(content: ByteArray?): FrameUi = FrameUi(
    id = id.value,
    session = session.value,
    widthPx = widthPx,
    heightPx = heightPx,
    masterWidthPx = masterWidthPx,
    masterHeightPx = masterHeightPx,
    bytes = bytes,
    estimatedTokens = estimatedTokens,
    format = format.name,
    content = content,
)

private const val JOURNAL_SIZE = 20
internal const val DEFAULT_PRESET: String = "overview"
