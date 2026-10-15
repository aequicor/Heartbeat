package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CapturePresets
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineKey
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseRecording
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.HostComputerControl
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.RecordRequest
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.TileRef
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.api.fitsRoi
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MAX_TYPED_CHARS
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MAX_WHEEL_NOTCHES
import io.aequicor.heartbeat.feature.computeruse.impl.domain.WHEEL_NOTCH_PX
import io.aequicor.heartbeat.feature.computeruse.impl.domain.inputWaitLimitMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * The hosted `computer_*` tools.
 *
 * Frame tools return the encoded image together with its geometry and token estimate in one response.
 * The host reads the stored preview before returning, so the engine needs no separate file-reading call.
 * Mutating tools go through the machine and its guards. A capture belongs to
 * the turn that opened it: other turns can neither use, switch nor release it. An authorized input call arms its
 * exact captured frame without a second manual switch, and input stays inside that area. Approvals show the
 * exact action, with control characters escaped and the text bounded.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ComputerUseAgentTools(
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val control: HostComputerControl,
    private val lifecycle: ComputerUseCaptureLifecycle,
    private val preferences: ComputerUsePreferences,
    private val stoppedTurns: ComputerUseStoppedTurns,
    private val frames: FrameStore,
) : AgentToolContribution {
    override val group: String = "computer"
    override val title: String = "Управление компьютером"
    override val catalog: List<ToolCatalogEntry> get() = computerToolSpecs(isRecordingEnabled = true).toolCatalog()
    override val legacyNames: Map<String, Set<String>> get() = legacyToolNames

    private val log = Log.tag("ComputerUseAgentTools")
    private val requests = Mutex()

    /** Computer control belongs to the profile and turn, so chat sessions do not need a project. */
    override val isDetachedSupported: Boolean = true

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (isEnabled()) computerToolSpecs(isRecording()) else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String {
        if (!isEnabled()) return ""
        return "Computer use drives the real screen for testing and debugging. Call computer_status first: it " +
            "reports permissions, monitors and the active capture. Two tool families drive the two capture modes: " +
            "computer_* works on the whole desktop (computer_capture, optionally one monitorId) for tasks that span " +
            "applications; window_* works on one application window (window_list, then window_capture {windowId}) " +
            "for work confined to it. Use the frame, ROI and input tools of the family whose capture is active; " +
            "the other family answers CaptureModeMismatch until you capture with it. " +
            "<family>_capture, _screenshot, _zoom and _roi include the image in their response; " +
            "inspect it directly without a separate file-reading call. A screenshot is downscaled " +
            "(at most ${OVERVIEW_WIDTH_PX}px) with a tile grid; read small text with <family>_zoom on an roi or " +
            "a zero-based tile (column:row, e.g. 0:0), cut from the master frame at native resolution. " +
            "The ROI (rectangle of interest) is your working area: <family>_roi {roi:{x,y,width,height}} " +
            "limits frames, zoom and input to that rectangle and frame pixels become relative to it; " +
            "clear it with {clear:true}. An roi is in pixels of the frame you last received unless roi.space " +
            "is \"master\" or \"normalized\". Pointer coordinates are pixels of the frame you last received " +
            "unless you pass space:\"master\", \"normalized\" or \"screen\"; points outside the ROI are refused " +
            "with OutsideRoi. <family>_type types up to " +
            "$MAX_TYPED_CHARS UTF-16 code units per call (a character outside the BMP counts as two, flags and " +
            "joined emoji more; newline is Enter, tab is Tab); split longer text. On Windows it " +
            "carries exact characters independent of the keyboard layout; on macOS it uses US key positions and " +
            "refuses other characters with UnsupportedCharacter. <family>_key accepts named keys including win; " +
            "$WHEEL_NOTCH_PX pixels of <family>_scroll deltaY are one wheel notch, at most $MAX_WHEEL_NOTCHES " +
            "notches per call, negative scrolls up. Input follows the session trust and confirmation gate " +
            "automatically; a refusal names the reason (PermissionLost, RegionOutOfBounds, OutsideRoi, " +
            "TargetClosed, ClientAreaUnavailable); StaleFrame or TargetResized requires a fresh screenshot. " +
            "A capture opened by another turn is refused with CaptureOwnedByAnotherTurn, and " +
            "StoppedByUser means the user stopped you: do not use the computer again in this turn. " +
            "Call <family>_release as soon as you finish working with the computer; capture and " +
            "stored frames are also released automatically when your turn ends." +
            if (isRecording()) RECORDING_INSTRUCTIONS else ""
    }

    override fun approval(spec: AgentToolSpec, arguments: JsonObject): AgentToolApproval =
        AgentToolApproval(spec.name, approvalDescription(spec, arguments))

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = approval(spec, arguments).copy(binding = binding())

    private fun binding(): String {
        val state = machines.find(ComputerUseMachineKey)?.state?.value
        return if (state is ComputerUseState.Capturing) {
            // Arming is the tool's own step, so it is not part of what the user approved.
            "${state.session.value}:${state.lastPreview?.id?.value.orEmpty()}"
        } else {
            state?.let { it::class.simpleName }.orEmpty()
        }
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        requests.withLock {
            try {
                val tool = ComputerTool.parse(name)
                refusal(context, tool, name, arguments)?.let { return@withLock failure(it) }
                val machine = started() ?: return@withLock failure("ComputerUseUnavailable")
                dispatch(machine, context, checkNotNull(tool), arguments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "hosted computer tool failed name=$name" }
                failure("ComputerUseToolFailed")
            }
        }

    /** The first reason this call cannot run at all, before the machine is touched. */
    private suspend fun refusal(
        context: AgentToolContext,
        tool: ComputerTool?,
        name: String,
        arguments: JsonObject,
    ): String? = when {
        !isEnabled() -> "Disabled"

        stoppedTurns.isStopped(context.owner()) -> {
            log.w { "computer tool refused: the user stopped this turn name=$name" }
            "StoppedByUser"
        }

        tool == null -> "UnknownTool"

        tool.needsRecording(arguments) && !isRecording() -> "RecordingDisabled"

        tool.isMutating() && context.authorization?.binding != binding() -> "CaptureChangedSinceApproval"

        else -> null
    }

    /** The dispatcher already revoked this turn's calls; the lifecycle guards cleanup on its own. */
    override suspend fun finishTurn(session: SessionRef, turn: TurnId) {
        lifecycle.finishTurn(CaptureOwner.Agent(session, turn))
    }

    private suspend fun dispatch(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        context: AgentToolContext,
        tool: ComputerTool,
        arguments: JsonObject,
    ): AgentToolResult = when (tool) {
        ComputerTool.Status -> status(machine)

        ComputerTool.WindowList -> windows()

        is ComputerTool.Family -> when {
            isForeignCapture(machine, context) -> {
                log.w { "computer tool refused: the capture belongs to another turn name=${tool.name}" }
                failure("CaptureOwnedByAnotherTurn")
            }

            isOtherFamily(machine, tool) -> {
                log.w { "computer tool refused: the active capture belongs to the other family name=${tool.name}" }
                failure("CaptureModeMismatch")
            }

            else -> dispatchOwned(machine, context, tool, arguments)
        }
    }

    private fun isForeignCapture(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        context: AgentToolContext,
    ): Boolean {
        val state = machine.state.value as? ComputerUseState.Capturing ?: return false
        return state.owner != context.owner()
    }

    /** Capture switches modes and release ends any own capture; every other verb needs a capture of its family. */
    private fun isOtherFamily(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        tool: ComputerTool.Family,
    ): Boolean {
        if (tool.verb == ToolVerb.Capture || tool.verb == ToolVerb.Release) return false
        val state = machine.state.value as? ComputerUseState.Capturing ?: return false
        return !tool.family.owns(state.mode)
    }

    private fun AgentToolContext.owner(): CaptureOwner.Agent = CaptureOwner.Agent(session, turn)

    /** Capture, input and release need an approval bound to the current capture; reading tools do not. */
    private fun ComputerTool.isMutating(): Boolean =
        this is ComputerTool.Family && (verb == ToolVerb.Capture || verb == ToolVerb.Release || verb.isInput)

    private suspend fun dispatchOwned(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        context: AgentToolContext,
        tool: ComputerTool.Family,
        arguments: JsonObject,
    ): AgentToolResult = when (tool.verb) {
        ToolVerb.Capture -> capture(machine, context, tool.family, arguments)

        ToolVerb.Screenshot -> screenshot(machine, arguments, context.owner())

        ToolVerb.Zoom -> zoom(machine, arguments, context.owner())

        ToolVerb.Roi -> roi(machine, arguments, context.owner())

        ToolVerb.Click -> input(machine, context, click(arguments), tool.name, arguments)

        ToolVerb.Drag -> input(machine, context, drag(arguments), tool.name, arguments)

        ToolVerb.Scroll -> input(machine, context, scroll(arguments), tool.name, arguments)

        ToolVerb.Type -> input(machine, context, typed(arguments), tool.name, arguments)

        ToolVerb.Key -> input(machine, context, keys(arguments), tool.name, arguments)

        ToolVerb.Release -> release(machine, context)

        ToolVerb.Record -> record(
            machine,
            recordRequest(arguments) ?: return failure("InvalidRecording"),
            context.owner(),
        )
    }

    private suspend fun isEnabled(): Boolean = toggles.get(ComputerUseEnabled) && preferences.read().isEnabled

    private suspend fun isRecording(): Boolean = toggles.get(ComputerUseRecording)

    /** `observeMs` belongs to the input tools only; elsewhere it is ignored, never refused. */
    private fun ComputerTool.needsRecording(arguments: JsonObject): Boolean {
        if (this !is ComputerTool.Family) return false
        val isObserved = verb.isInput && (arguments.int(OBSERVE_ARGUMENT) ?: 0) > 0
        return verb.isRecording || isObserved
    }

    /** The machine is created lazily; a tool call is what starts the availability probe. */
    private suspend fun started(): MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>? {
        val machine = machines.find(ComputerUseMachineKey) ?: return null
        when (machine.state.value) {
            ComputerUseState.Idle -> machine.send(ComputerUseIntent.Public.Start)
            is ComputerUseState.Unavailable, is ComputerUseState.Failed -> machine.send(ComputerUseIntent.Public.Retry)
            ComputerUseState.Checking, is ComputerUseState.Ready, is ComputerUseState.Capturing -> Unit
        }
        return withTimeoutOrNull(START_TIMEOUT_MILLIS) {
            machine.state.first { it !is ComputerUseState.Idle && it !is ComputerUseState.Checking }
            machine
        }
    }

    private suspend fun status(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
    ): AgentToolResult {
        val status = control.status()
        val capturing = machine.state.value as? ComputerUseState.Capturing
        return AgentToolResult(
            buildJsonObject {
                put("captureAvailable", status.capabilities.isCaptureAvailable)
                put("windowCaptureAvailable", status.capabilities.isWindowCaptureAvailable)
                put("clientAreaCaptureAvailable", status.capabilities.isClientAreaCaptureAvailable)
                put(
                    "monitors",
                    buildJsonArray {
                        status.capabilities.monitors.forEach { monitor ->
                            add(
                                buildJsonObject {
                                    put("id", monitor.id.value)
                                    put("bounds", boundsJson(monitor.bounds))
                                    put("scale", monitor.bounds.scale)
                                    put("primary", monitor.isPrimary)
                                },
                            )
                        }
                    },
                )
                (status.mode as? ComputerUseMode.Window)?.let { mode ->
                    val target = control.resolveWindow(mode.target.id)
                    put("clientAreaGeometryAvailable", target?.clientBounds != null)
                    val bounds = target?.clientBounds
                    if (bounds != null) put("clientBounds", boundsJson(bounds))
                }
                put("inputAvailable", status.capabilities.isInputAvailable)
                put("desktopInputAllowed", status.capabilities.isDesktopInputAllowed)
                put("inputArmed", status.isInputArmed)
                put("mode", status.mode?.let { it::class.simpleName })
                put("family", status.mode?.let { ToolFamily.of(it).prefix })
                capturing?.roi?.let { put("roi", roiJson(it)) }
                put("blockers", buildJsonArray { status.capabilities.blockers.forEach { add(it.name) } })
                status.lastPreview?.let { put("frame", frameJson(it)) }
            }.toString(),
        )
    }

    private fun roiJson(roi: CaptureRegion): JsonObject = buildJsonObject {
        put("x", roi.x)
        put("y", roi.y)
        put("width", roi.widthPx)
        put("height", roi.heightPx)
    }

    private fun boundsJson(bounds: ScreenBounds): JsonObject = buildJsonObject {
        put("x", bounds.x)
        put("y", bounds.y)
        put("widthPx", bounds.widthPx)
        put("heightPx", bounds.heightPx)
        put("scale", bounds.scale)
    }

    private suspend fun windows(): AgentToolResult {
        val targets = control.windows()
        val payload = buildJsonArray {
            targets.take(MAX_WINDOWS).forEach { target ->
                add(
                    buildJsonObject {
                        put("windowId", target.id.value)
                        put("application", target.application)
                        put("title", target.title)
                        put("x", target.bounds.x)
                        put("y", target.bounds.y)
                        put("widthPx", target.bounds.widthPx)
                        put("heightPx", target.bounds.heightPx)
                        put("minimized", target.isMinimized)
                        put("clientAreaGeometryAvailable", target.clientBounds != null)
                        target.clientBounds?.let { put("clientBounds", boundsJson(it)) }
                    },
                )
            }
        }
        log.d { "window list served count=${targets.size}" }
        return AgentToolResult(payload.toString())
    }

    private suspend fun capture(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        context: AgentToolContext,
        family: ToolFamily,
        arguments: JsonObject,
    ): AgentToolResult {
        val mode = mode(family, arguments) ?: return failure(
            if (family == ToolFamily.Window) "UnknownWindow" else "UnknownMonitor",
        )
        if (mode is ComputerUseMode.Window && mode.target.isSelfOwned) {
            return failure(ComputerUseFailure.SelfCaptureNotAllowed.name)
        }
        val session = CaptureSessionId(Uuid.random().toString())
        val owner = context.owner()
        val intent = when (machine.state.value) {
            is ComputerUseState.Capturing -> ComputerUseIntent.Public.SwitchMode(
                mode,
                session,
                owner,
                authorizedSession(context),
            )

            ComputerUseState.Idle, ComputerUseState.Checking, is ComputerUseState.Unavailable,
            is ComputerUseState.Ready, is ComputerUseState.Failed,
            -> ComputerUseIntent.Public.BeginCapture(
                mode,
                owner,
                session,
            )
        }
        val sent = lifecycle.begin(machine, owner, session, intent, context.lifetime)
        return if (sent != SendResult.Accepted) {
            log.w { "capture refused result=$sent" }
            failure("CaptureRefused")
        } else {
            openedCapture(machine, arguments, session, owner)
        }
    }

    private suspend fun openedCapture(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        arguments: JsonObject,
        session: CaptureSessionId,
        owner: CaptureOwner.Agent,
    ): AgentToolResult {
        var isCompleted = false
        try {
            // A stopped or replaced session never opens; its closure ends the wait as well.
            val settled = withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) {
                machine.state.first { it !is ComputerUseState.Capturing || it.session != session || it.isOpen }
            }
            val result = when {
                settled == null -> {
                    machine.send(ComputerUseIntent.Public.CancelSession(session, owner))
                    failure("CaptureTimedOut")
                }

                settled is ComputerUseState.Failed && settled.session == session -> failure(settled.reason.name)

                settled !is ComputerUseState.Capturing || settled.session != session -> {
                    // The state carries the stop atomically with the transition that ended the session.
                    val isStopped = (settled as? ComputerUseState.Ready)?.stoppedOwners?.contains(owner) == true
                    failure(if (isStopped || stoppedTurns.isStopped(owner)) "StoppedByUser" else "CaptureEnded")
                }

                else -> screenshot(machine, arguments, owner)
            }
            isCompleted = true
            return result
        } finally {
            if (!isCompleted) {
                withContext(NonCancellable) { machine.send(ComputerUseIntent.Public.CancelSession(session, owner)) }
            }
        }
    }

    private suspend fun screenshot(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        arguments: JsonObject,
        owner: CaptureOwner.Agent,
    ): AgentToolResult {
        val state = machine.state.value as? ComputerUseState.Capturing ?: return failure("NotCapturing")
        val roi = when (val argument = roiArgument(arguments, state.lastPreview)) {
            RoiArgument.Absent -> null
            RoiArgument.Invalid -> return failure("InvalidRoi")
            is RoiArgument.Master -> argument.region
        }
        val tile = arguments.text("tile")?.let { TileRef.parse(it) }
        if ("tile" in arguments && tile == null) return failure("InvalidTile")
        if (roi != null && tile != null) return failure("InvalidRoi")
        if (roi != null && !roi.fitsRoi(state.roi)) return failure(ComputerUseFailure.OutsideRoi.name)
        val request = CaptureRequest(
            roi = roi,
            tile = tile,
            encoding = encoding(arguments, CapturePresets.AgentOverview),
            isCursorIncluded = arguments.flag("includeCursor") ?: true,
            isFresh = arguments.flag("fresh") ?: true,
        )
        return frame(
            machine,
            ComputerUseIntent.Public.Capture(request, expectedSession = state.session, expectedOwner = owner),
            request.encoding,
        )
    }

    private suspend fun zoom(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        arguments: JsonObject,
        owner: CaptureOwner.Agent,
    ): AgentToolResult {
        val state = machine.state.value as? ComputerUseState.Capturing ?: return failure("NotCapturing")
        val master = state.master ?: return failure("NoMasterFrame")
        val identifier = arguments.text("captureId")?.let { CaptureId(it) } ?: master.id
        val region = when (val geometry = areaArgument(arguments, state, identifier)) {
            AreaArgument.Invalid -> return failure("InvalidCrop")
            is AreaArgument.Area -> geometry.region
        }
        if (region != null && !region.fitsRoi(state.roi)) return failure(ComputerUseFailure.OutsideRoi.name)
        val request = try {
            CropRequest(
                capture = identifier,
                roi = region,
                scale = arguments.number("scale") ?: 1.0,
                encoding = encoding(arguments, CapturePresets.AgentDetail),
            )
        } catch (e: IllegalArgumentException) {
            log.w(e) { "crop request refused" }
            return failure("InvalidCrop")
        }
        return frame(
            machine,
            ComputerUseIntent.Public.Crop(request, expectedSession = state.session, expectedOwner = owner),
            request.encoding,
        )
    }

    /** Sets or clears the active ROI, then serves a fresh frame of it. */
    private suspend fun roi(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        arguments: JsonObject,
        owner: CaptureOwner.Agent,
    ): AgentToolResult {
        val state = machine.state.value as? ComputerUseState.Capturing ?: return failure("NotCapturing")
        val master = state.master ?: return failure("NoMasterFrame")
        val roi = if (arguments.flag("clear") == true) {
            if (arguments.hasAny(areaKeys)) return failure("InvalidRoi")
            null
        } else {
            when (val geometry = areaArgument(arguments, state, master.id)) {
                AreaArgument.Invalid -> return failure("InvalidRoi")
                is AreaArgument.Area -> geometry.region ?: return failure("InvalidRoi")
            }
        }
        val bounds = CaptureRegion(0, 0, master.masterWidthPx, master.masterHeightPx)
        if (roi != null && !roi.isInside(bounds)) return failure(ComputerUseFailure.RegionOutOfBounds.name)
        val sent = machine.send(ComputerUseIntent.Public.SetRoi(roi, state.session, owner))
        if (sent != SendResult.Accepted) {
            log.w { "roi refused result=$sent" }
            return failure("CaptureEnded")
        }
        log.i { "roi ${if (roi == null) "cleared" else "set area=${roi.widthPx}x${roi.heightPx}"}" }
        return screenshot(machine, JsonObject(emptyMap()), owner)
    }

    private suspend fun frame(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        intent: ComputerUseIntent.Public,
        encoding: CaptureEncoding,
    ): AgentToolResult {
        val output = awaitOutput(machine, intent) ?: return failure("FrameTimedOut")
        return when (output) {
            is ComputerUseOutput.FrameReady -> frames.agentToolFrame(output)

            is ComputerUseOutput.Rejected -> failure(output.reason.name)

            is ComputerUseOutput.SessionClosed -> failure(output.reason?.name ?: "CaptureEnded")

            is ComputerUseOutput.InputApplied, is ComputerUseOutput.ClipReady, is ComputerUseOutput.CaptureChanged,
            is ComputerUseOutput.PermissionRequired, ComputerUseOutput.Revoked,
            -> failure("UnexpectedOutput")
        }.also { log.d { "frame served format=${encoding.format}" } }
    }

    private fun frameJson(reference: CaptureRef): JsonObject = buildJsonObject {
        put("captureId", reference.id.value)
        put("widthPx", reference.widthPx)
        put("heightPx", reference.heightPx)
        put("format", reference.format.name.lowercase())
        put("estimatedTokens", reference.estimatedTokens)
    }

    private suspend fun input(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        context: AgentToolContext,
        action: InputAction?,
        name: String,
        arguments: JsonObject,
    ): AgentToolResult {
        if (action == null) return failure("InvalidAction")
        val session = authorizedSession(context) ?: return failure("CaptureChangedSinceApproval")
        val capture = context.authorization?.binding?.split(':')?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }?.let(::CaptureId)
        val owner = context.owner()
        val armed = machine.send(ComputerUseIntent.Public.ArmInput(true, session, capture, owner))
        if (armed != SendResult.Accepted) {
            val state = machine.state.value
            val reason = if (state is ComputerUseState.Capturing && !state.capabilities.isInputAvailable) {
                ComputerUseFailure.PermissionLost.name
            } else {
                "CaptureChangedSinceApproval"
            }
            log.w { "input arming refused reason=$reason" }
            return failure(reason)
        }
        val output = awaitOutput(
            machine,
            ComputerUseIntent.Public.Input(
                action,
                expectedSession = session,
                expectedCapture = capture,
                expectedOwner = owner,
            ),
            inputWaitLimitMillis(action),
        )
            ?: return failure("InputTimedOut")
        return when (output) {
            is ComputerUseOutput.InputApplied -> observe(machine, arguments, owner, name)

            is ComputerUseOutput.Rejected -> failure(output.reason.name)

            is ComputerUseOutput.SessionClosed -> failure(output.reason?.name ?: "CaptureEnded")

            is ComputerUseOutput.FrameReady, is ComputerUseOutput.ClipReady, is ComputerUseOutput.CaptureChanged,
            is ComputerUseOutput.PermissionRequired, ComputerUseOutput.Revoked,
            -> failure("UnexpectedOutput")
        }
    }

    /**
     * After an applied input with `observeMs`, records until the screen settles. The input already happened, so a
     * failed recording is reported next to the success instead of as an error.
     */
    private suspend fun observe(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        arguments: JsonObject,
        owner: CaptureOwner.Agent,
        name: String,
    ): AgentToolResult {
        val millis = arguments.int(OBSERVE_ARGUMENT)?.toLong()?.takeIf { it > 0 }
            ?: return AgentToolResult("Applied $name")
        val request = RecordRequest(
            maxDurationMillis = millis.coerceIn(RecordRequest.MIN_DURATION_MILLIS, MAX_OBSERVE_MILLIS),
        )
        val observed = record(machine, request, owner, prefix = "Applied $name")
        val failure = "Applied $name; observation failed: ${observed.text}"
        return if (observed.isError) {
            AgentToolResult(failure)
        } else {
            observed
        }
    }

    private suspend fun record(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        request: RecordRequest,
        owner: CaptureOwner.Agent,
        prefix: String? = null,
    ): AgentToolResult {
        val state = machine.state.value as? ComputerUseState.Capturing ?: return failure("NotCapturing")
        val intent = ComputerUseIntent.Public.Record(request, expectedSession = state.session, expectedOwner = owner)
        val output = awaitOutput(machine, intent) ?: return failure("RecordTimedOut")
        return when (output) {
            is ComputerUseOutput.ClipReady -> frames.agentToolClip(output.clip, prefix)

            is ComputerUseOutput.Rejected -> failure(output.reason.name)

            is ComputerUseOutput.SessionClosed -> failure(output.reason?.name ?: "CaptureEnded")

            is ComputerUseOutput.FrameReady, is ComputerUseOutput.InputApplied, is ComputerUseOutput.CaptureChanged,
            is ComputerUseOutput.PermissionRequired, ComputerUseOutput.Revoked,
            -> failure("UnexpectedOutput")
        }.also {
            val count = (output as? ComputerUseOutput.ClipReady)?.clip?.frames?.size ?: 0
            log.d { "clip served outcome=${output::class.simpleName.orEmpty()} frames=$count" }
        }
    }

    private fun authorizedSession(context: AgentToolContext): CaptureSessionId? =
        context.authorization?.binding?.split(':')?.takeIf { it.size == BINDING_FIELDS }
            ?.first()?.let(::CaptureSessionId)

    private suspend fun release(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        context: AgentToolContext,
    ): AgentToolResult = coroutineScope {
        val session = authorizedSession(context) ?: return@coroutineScope failure("NotCapturing")
        val closed = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.first { it is ComputerUseOutput.SessionClosed && it.session == session }
        }
        try {
            val sent = machine.send(ComputerUseIntent.Public.CancelSession(session, context.owner()))
            if (sent != SendResult.Accepted) return@coroutineScope failure("CaptureChangedSinceApproval")
            if (withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) { closed.await() } == null) {
                failure("CleanupTimedOut")
            } else {
                AgentToolResult("Capture stopped and its frames deleted")
            }
        } finally {
            closed.cancel()
        }
    }

    private suspend fun mode(family: ToolFamily, arguments: JsonObject): ComputerUseMode? = when (family) {
        ToolFamily.Desktop -> desktopMode(arguments)
        ToolFamily.Window -> windowMode(arguments)
    }

    /** A named monitor must exist; without one the capture covers every monitor. */
    private suspend fun desktopMode(arguments: JsonObject): ComputerUseMode? {
        val isCursorIncluded = arguments.flag("includeCursor") ?: true
        val requested = arguments.text("monitorId")
            ?: return ComputerUseMode.Desktop(isCursorIncluded = isCursorIncluded)
        val monitor = control.status().capabilities.monitors.firstOrNull { it.id.value == requested } ?: return null
        return ComputerUseMode.Desktop(monitor.id, isCursorIncluded)
    }

    /** Resolves the requested window identity against the live window list. */
    private suspend fun windowMode(arguments: JsonObject): ComputerUseMode? {
        val identifier = arguments.text("windowId")?.let { WindowId(it) }
        val target = identifier?.let { id -> control.resolveWindow(id) }
        return target?.let { ComputerUseMode.Window(it, isClientAreaOnly = arguments.flag("clientAreaOnly") ?: false) }
    }

    private fun failure(code: String): AgentToolResult = AgentToolResult(code, isError = true)

    private companion object {
        val areaKeys = setOf("roi", "tile")

        const val RECORDING_INSTRUCTIONS = " Video mode: <family>_record {durationMs, fps, untilStable, maxFrames} " +
            "records the active ROI and returns a few keyframes (images in order, with tMs) cropped to the area " +
            "that changed; the last keyframe is the current frame for input coordinates. Input tools take " +
            "observeMs to record right after the action, e.g. to watch a menu open or a page load; " +
            "prefer it to repeated screenshots while the screen animates."
        const val START_TIMEOUT_MILLIS = 5_000L
        const val CAPTURE_TIMEOUT_MILLIS = 10_000L
        const val MAX_WINDOWS = 60
        const val BINDING_FIELDS = 2
    }
}
