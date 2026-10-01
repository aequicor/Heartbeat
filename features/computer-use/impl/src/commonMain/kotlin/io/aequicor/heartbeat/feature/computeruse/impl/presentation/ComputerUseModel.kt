package io.aequicor.heartbeat.feature.computeruse.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CapturePresets
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import kotlin.uuid.Uuid

private typealias PanelPipeline =
    PipelineContext<ComputerUseScreenState, ComputerUseScreenIntent, ComputerUseScreenAction>

/** Lifecycle phase of the feature, mirrored from the machine. */
internal enum class PhaseUi { Idle, Checking, Blocked, Ready, Capturing, Failed }

/** Capture mode the panel offers. */
internal enum class ModeUi { Desktop, Window }

/** Why the panel shows a warning; the screen maps it to a localized string. */
internal enum class RejectionUi { NotArmed, OutsideCapture, TargetClosed, FrameExpired, TooLarge, Unavailable, Other }

/** One panel message. */
internal sealed interface PanelMessage {
    /** A request was refused. */
    data class Rejected(val reason: RejectionUi) : PanelMessage

    /** The operating system refuses capture or input. */
    data class Blocked(val blockers: ImmutableList<String>) : PanelMessage

    /** The kill switch stopped the capture and deleted its frames. */
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

/** Immutable presentation state of the panel. */
@Immutable
internal data class ComputerUseScreenState(
    val phase: PhaseUi = PhaseUi.Idle,
    val mode: ModeUi = ModeUi.Desktop,
    val targets: ImmutableList<WindowRowUi> = persistentListOf(),
    val selectedWindowId: String? = null,
    val isInputArmed: Boolean = false,
    val isInputAvailable: Boolean = false,
    val isWindowModeAvailable: Boolean = false,
    val preset: String = DEFAULT_PRESET,
    val frame: FrameUi? = null,
    val journal: ImmutableList<String> = persistentListOf(),
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
    private val frames: FrameStore,
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
        reduce { intent -> onIntent(intent) }
    }

    init {
        store.start(scope)
        scope.launch {
            store.intent(ComputerUseScreenIntent.PresetLoaded(preferences.read().preset))
            machine.send(ComputerUseIntent.Public.Start)
        }
    }

    private suspend fun PanelPipeline.onOutput(output: ComputerUseOutput) {
        when (output) {
            is ComputerUseOutput.FrameReady -> {
                val content = frames.read(output.capture.path)
                updateState {
                    copy(
                        frame = FrameUi(
                            id = output.capture.id.value,
                            widthPx = output.capture.widthPx,
                            heightPx = output.capture.heightPx,
                            masterWidthPx = output.capture.masterWidthPx,
                            masterHeightPx = output.capture.masterHeightPx,
                            bytes = output.capture.bytes,
                            estimatedTokens = output.capture.estimatedTokens,
                            format = output.capture.format.name,
                            content = content,
                        ),
                        message = null,
                    )
                }
            }

            is ComputerUseOutput.Rejected ->
                updateState { copy(message = PanelMessage.Rejected(output.reason.rejection())) }

            is ComputerUseOutput.PermissionRequired -> updateState {
                copy(message = PanelMessage.Blocked(output.blockers.map { it.name }.toImmutableList()))
            }

            is ComputerUseOutput.InputApplied ->
                updateState { copy(journal = journal.with(output.action.entry())) }

            is ComputerUseOutput.CaptureChanged -> updateState { copy(message = null) }

            ComputerUseOutput.Revoked -> updateState {
                copy(frame = null, journal = persistentListOf(), message = PanelMessage.Revoked)
            }
        }
    }

    // PipelineContext is FlowMVI's pipeline receiver (itself a CoroutineScope); store DSL functions extend it.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun PanelPipeline.onIntent(intent: ComputerUseScreenIntent) {
        when (intent) {
            is ComputerUseScreenIntent.SelectMode -> updateState { copy(mode = intent.mode, message = null) }

            is ComputerUseScreenIntent.SelectWindow ->
                updateState { copy(selectedWindowId = intent.id, message = null) }

            ComputerUseScreenIntent.Refresh -> sendTo(machine, ComputerUseIntent.Public.RefreshTargets)

            ComputerUseScreenIntent.StartCapture -> startCapture()

            ComputerUseScreenIntent.CaptureFrame -> captureFrame()

            ComputerUseScreenIntent.StopCapture -> sendTo(machine, ComputerUseIntent.Public.EndCapture)

            is ComputerUseScreenIntent.ArmInput ->
                sendTo(machine, ComputerUseIntent.Public.ArmInput(intent.isArmed))

            is ComputerUseScreenIntent.SelectPreset -> selectPreset(intent.name)

            is ComputerUseScreenIntent.PresetLoaded -> updateState { copy(preset = intent.name) }

            ComputerUseScreenIntent.Revoke -> sendTo(machine, ComputerUseIntent.Public.Revoke)

            ComputerUseScreenIntent.Retry -> sendTo(machine, ComputerUseIntent.Public.Retry)

            ComputerUseScreenIntent.DismissMessage -> updateState { copy(message = null) }
        }
    }

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
        val intent = when (machine.state.value) {
            is ComputerUseState.Capturing -> ComputerUseIntent.Public.SwitchMode(mode, session)
            else -> ComputerUseIntent.Public.BeginCapture(mode, CaptureOwner.Panel, session)
        }
        sendTo(machine, intent) { rejected -> log.w { "capture start rejected result=$rejected" } }
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

    private suspend fun PanelPipeline.captureFrame() {
        var presetName = DEFAULT_PRESET
        withState { presetName = preset }
        val settings = preferences.read()
        val encoding = CapturePresets.byName(presetName) ?: settings.encoding
        sendTo(
            machine,
            ComputerUseIntent.Public.Capture(
                CaptureRequest(encoding = encoding, includeCursor = settings.isCursorIncluded),
            ),
        )
    }

    private suspend fun PanelPipeline.selectPreset(name: String) {
        if (preferences.setPreset(name)) {
            updateState { copy(preset = name) }
        } else {
            updateState { copy(message = PanelMessage.Rejected(RejectionUi.Other)) }
        }
    }
}

private fun ComputerUseScreenState.reflectState(state: ComputerUseState): ComputerUseScreenState = when (state) {
    ComputerUseState.Idle -> copy(phase = PhaseUi.Idle, isInputArmed = false)

    ComputerUseState.Checking -> copy(phase = PhaseUi.Checking)

    is ComputerUseState.Unavailable -> copy(
        phase = PhaseUi.Blocked,
        message = PanelMessage.Blocked(state.blockers.map { it.name }.toImmutableList()),
    )

    is ComputerUseState.Ready -> copy(
        phase = PhaseUi.Ready,
        targets = state.targets.toRows(),
        isInputArmed = state.isInputArmed,
        isInputAvailable = state.capabilities.isInputAvailable,
        isWindowModeAvailable = state.capabilities.isWindowCaptureAvailable,
        frameCount = 0L,
    )

    is ComputerUseState.Capturing -> copy(
        phase = PhaseUi.Capturing,
        mode = if (state.mode is ComputerUseMode.Window) ModeUi.Window else ModeUi.Desktop,
        targets = state.targets.toRows(),
        isInputArmed = state.isInputArmed,
        isInputAvailable = state.capabilities.isInputAvailable,
        isWindowModeAvailable = state.capabilities.isWindowCaptureAvailable,
        frameCount = state.frameCount,
    )

    is ComputerUseState.Failed -> copy(
        phase = PhaseUi.Failed,
        message = PanelMessage.Rejected(state.reason.rejection()),
        isInputArmed = false,
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

private fun InputAction.entry(): String = when (this) {
    is InputAction.MoveTo -> "move"
    is InputAction.Click -> "click $button x$count"
    is InputAction.Drag -> "drag $button"
    is InputAction.Scroll -> "scroll $deltaX,$deltaY"
    is InputAction.Type -> "type ${text.length} chars"
    is InputAction.Key -> "key ${keys.size}"
}

private fun ImmutableList<String>.with(entry: String): ImmutableList<String> =
    (this + entry).takeLast(JOURNAL_SIZE).toImmutableList()

private const val JOURNAL_SIZE = 20
internal const val DEFAULT_PRESET: String = "overview"
