package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
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
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.FrameSpace
import io.aequicor.heartbeat.feature.computeruse.api.HostComputerControl
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.MouseButton
import io.aequicor.heartbeat.feature.computeruse.api.NormalizedRegion
import io.aequicor.heartbeat.feature.computeruse.api.TileGrid
import io.aequicor.heartbeat.feature.computeruse.api.TileRef
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * The hosted `computer_*` tools.
 *
 * Read-only tools return the path of a stored frame plus its geometry and token estimate; an engine that can
 * read files opens the frame itself. Mutating tools go through the machine, so the same guards apply whether the
 * command came from an agent or from the panel. An authorized input call arms its exact captured frame without
 * a second manual switch, and input stays inside that area. Approvals show the exact action, with control
 * characters escaped and the text bounded.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ComputerUseAgentTools(
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val control: HostComputerControl,
    private val lifecycle: ComputerUseCaptureLifecycle,
    private val preferences: ComputerUsePreferences,
) : AgentToolContribution {
    private val log = Log.tag("ComputerUseAgentTools")
    private val requests = Mutex()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (isEnabled()) toolSpecs else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String {
        if (!isEnabled()) return ""
        return "Computer use drives the real screen for testing and debugging. Call computer_status first: it " +
            "reports permissions and the active capture. Choose the capture mode yourself: use one application " +
            "window for work confined to it, or the desktop when the task spans applications. Capture the desktop " +
            "with computer_capture {mode:\"desktop\"} or one application window with " +
            "computer_capture {mode:\"window\", windowId} after picking an id from computer_windows. " +
            "computer_screenshot returns a downscaled frame (at most ${OVERVIEW_WIDTH_PX}px wide) and a tile grid; " +
            "read small text with computer_zoom on a region or a tile, which is cut from the same stored master " +
            "frame at native resolution. Pointer coordinates are pixels of the frame you last received unless " +
            "you pass space:\"master\", \"normalized\" or \"screen\". Input follows the session trust and " +
            "confirmation gate automatically; a refusal names the reason (PermissionLost, RegionOutOfBounds, " +
            "TargetClosed). Call computer_release as soon as you finish working with the computer; capture and " +
            "stored frames are also released automatically when your turn ends."
    }

    override fun approval(spec: AgentToolSpec, arguments: JsonObject): AgentToolApproval {
        val description = when (spec.name) {
            CLICK_TOOL -> describe(spec.name, arguments, "x", "y")

            DRAG_TOOL -> "${describe(
                spec.name,
                arguments,
                "x",
                "y",
            )} to ${arguments.raw("toX").orEmpty()},${arguments.raw("toY").orEmpty()}"

            SCROLL_TOOL -> "${describe(
                spec.name,
                arguments,
                "x",
                "y",
            )} delta=${arguments.raw("deltaX").orEmpty()},${arguments.raw("deltaY").orEmpty()}"

            TYPE_TOOL -> describeText(arguments.text("text").orEmpty())

            KEY_TOOL -> "Press ${visible(arguments.raw("keys"))}"

            CAPTURE_TOOL -> "Start capturing ${visible(arguments.raw("mode"))} " +
                "windowId=${visible(arguments.text("windowId"))} " +
                "clientAreaOnly=${arguments.flag("clientAreaOnly") ?: false} " +
                "includeCursor=${arguments.flag("includeCursor") ?: true}"

            RELEASE_TOOL -> "Stop the capture and delete its stored frames"

            else -> spec.description
        }
        return AgentToolApproval(spec.name, description)
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = approval(spec, arguments).copy(binding = binding())

    private fun binding(): String {
        val state = machines.find(ComputerUseMachineKey)?.state?.value
        return if (state is ComputerUseState.Capturing) {
            "${state.session.value}:${state.lastPreview?.id?.value.orEmpty()}:${state.isInputArmed}"
        } else {
            state?.let { it::class.simpleName }.orEmpty()
        }
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        requests.withLock {
            try {
                if (!isEnabled()) return@withLock failure("Disabled")
                if (name in mutatingTools && context.authorization?.binding != binding()) {
                    return@withLock failure("CaptureChangedSinceApproval")
                }
                val machine = started() ?: return@withLock failure("ComputerUseUnavailable")
                dispatch(machine, context, name, arguments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "hosted computer tool failed name=$name" }
                failure("ComputerUseToolFailed")
            }
        }

    override suspend fun finishTurn(session: SessionRef, turn: TurnId): Unit = requests.withLock {
        lifecycle.finishTurn(CaptureOwner.Agent(session, turn))
    }

    private suspend fun dispatch(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
    ): AgentToolResult = when (name) {
        STATUS_TOOL -> status()
        WINDOWS_TOOL -> windows()
        CAPTURE_TOOL -> capture(machine, context, arguments)
        SCREENSHOT_TOOL -> screenshot(machine, arguments)
        ZOOM_TOOL -> zoom(machine, arguments)
        CLICK_TOOL -> input(machine, context, click(arguments), name)
        DRAG_TOOL -> input(machine, context, drag(arguments), name)
        SCROLL_TOOL -> input(machine, context, scroll(arguments), name)
        TYPE_TOOL -> input(machine, context, typed(arguments), name)
        KEY_TOOL -> input(machine, context, keys(arguments), name)
        RELEASE_TOOL -> release(machine, context)
        else -> failure("UnknownTool")
    }

    private suspend fun isEnabled(): Boolean = toggles.get(ComputerUseEnabled) && preferences.read().isEnabled

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

    private suspend fun status(): AgentToolResult {
        val status = control.status()
        return AgentToolResult(
            buildJsonObject {
                put("captureAvailable", status.capabilities.isCaptureAvailable)
                put("windowCaptureAvailable", status.capabilities.isWindowCaptureAvailable)
                put("inputAvailable", status.capabilities.isInputAvailable)
                put("desktopInputAllowed", status.capabilities.isDesktopInputAllowed)
                put("inputArmed", status.isInputArmed)
                put("mode", status.mode?.let { it::class.simpleName })
                put("blockers", buildJsonArray { status.capabilities.blockers.forEach { add(it.name) } })
                status.lastPreview?.let { put("frame", frameJson(it)) }
            }.toString(),
        )
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
        arguments: JsonObject,
    ): AgentToolResult {
        val mode = mode(arguments) ?: return failure("InvalidMode")
        val session = CaptureSessionId(Uuid.random().toString())
        val owner = CaptureOwner.Agent(context.session, context.turn)
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
            openedCapture(machine, arguments, session)
        }
    }

    private suspend fun openedCapture(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        arguments: JsonObject,
        session: CaptureSessionId,
    ): AgentToolResult {
        var isCompleted = false
        try {
            val opened = withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) {
                machine.state.first { it is ComputerUseState.Capturing && it.session == session && it.isOpen }
            }
            if (opened == null) {
                machine.send(ComputerUseIntent.Public.CancelSession(session))
                return failure("CaptureTimedOut")
            }
            val result = screenshot(machine, arguments)
            isCompleted = true
            return result
        } finally {
            if (!isCompleted) {
                withContext(NonCancellable) { machine.send(ComputerUseIntent.Public.CancelSession(session)) }
            }
        }
    }

    private suspend fun screenshot(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        arguments: JsonObject,
    ): AgentToolResult {
        val state = machine.state.value as? ComputerUseState.Capturing ?: return failure("NotCapturing")
        val region = region(arguments)
        val tile = arguments.text("tile")?.let { TileRef.parse(it) }
        if (arguments.hasAny(regionKeys) && region == null) return failure("InvalidRegion")
        if ("tile" in arguments && tile == null) return failure("InvalidTile")
        val request = CaptureRequest(
            region = region,
            tile = tile,
            encoding = encoding(arguments, CapturePresets.AgentOverview),
            isCursorIncluded = arguments.flag("includeCursor") ?: true,
            isFresh = arguments.flag("fresh") ?: true,
        )
        return frame(
            machine,
            ComputerUseIntent.Public.Capture(request, expectedSession = state.session),
            request.encoding,
        )
    }

    private suspend fun zoom(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        arguments: JsonObject,
    ): AgentToolResult {
        val state = machine.state.value as? ComputerUseState.Capturing ?: return failure("NotCapturing")
        val master = state.master ?: return failure("NoMasterFrame")
        val identifier = arguments.text("captureId")?.let { CaptureId(it) } ?: master.id
        val geometry = cropGeometry(arguments, state, identifier) ?: return failure("InvalidCrop")
        val scale = arguments.number("scale") ?: 1.0
        val request = try {
            CropRequest(
                capture = identifier,
                region = geometry.region,
                normalized = geometry.normalized,
                scale = scale,
                encoding = encoding(arguments, CapturePresets.AgentDetail),
            )
        } catch (e: IllegalArgumentException) {
            log.w(e) { "crop request refused" }
            return failure("InvalidCrop")
        }
        return frame(
            machine,
            ComputerUseIntent.Public.Crop(request, expectedSession = state.session),
            request.encoding,
        )
    }

    private fun cropGeometry(
        arguments: JsonObject,
        state: ComputerUseState.Capturing,
        identifier: CaptureId,
    ): CropGeometry? {
        val tile = arguments.text("tile")?.let { TileRef.parse(it) }
        if ("tile" in arguments && tile == null) return null
        val region = region(arguments)
        val normalized = normalized(arguments)
        if (arguments.hasAny(regionKeys) && region == null) return null
        if (arguments.hasAny(normalizedKeys) && normalized == null) return null
        val explicit = if (tile == null) region else tileRegion(tile, state, identifier) ?: return null
        return CropGeometry(explicit, normalized)
    }

    private fun tileRegion(tile: TileRef, state: ComputerUseState.Capturing, identifier: CaptureId): CaptureRegion? {
        val source = listOfNotNull(state.master, state.lastPreview, state.lastCrop)
            .firstOrNull { it.id == identifier } ?: return null
        return TileGrid.forMaster(source.masterWidthPx, source.masterHeightPx)
            .region(tile.column, tile.row, source.masterWidthPx, source.masterHeightPx)
    }

    private data class CropGeometry(val region: CaptureRegion?, val normalized: NormalizedRegion?)

    private suspend fun frame(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        intent: ComputerUseIntent.Public,
        encoding: CaptureEncoding,
    ): AgentToolResult {
        val output = awaitOutput(machine, intent) ?: return failure("FrameTimedOut")
        return when (output) {
            is ComputerUseOutput.FrameReady -> AgentToolResult(frameText(output.capture, output.tiles, output.master))

            is ComputerUseOutput.Rejected -> failure(output.reason.name)

            is ComputerUseOutput.InputApplied, is ComputerUseOutput.CaptureChanged,
            is ComputerUseOutput.PermissionRequired, is ComputerUseOutput.SessionClosed, ComputerUseOutput.Revoked,
            -> failure("UnexpectedOutput")
        }.also { log.d { "frame served format=${encoding.format}" } }
    }

    private fun frameText(reference: CaptureRef, tiles: TileGrid?, master: CaptureRef?): String = buildJsonObject {
        put("captureId", reference.id.value)
        put("masterCaptureId", master?.id?.value)
        put("path", reference.path)
        put("format", reference.format.name.lowercase())
        put("widthPx", reference.widthPx)
        put("heightPx", reference.heightPx)
        put("bytes", reference.bytes)
        put("estimatedTokens", reference.estimatedTokens)
        put("masterWidthPx", reference.masterWidthPx)
        put("masterHeightPx", reference.masterHeightPx)
        put("previewScale", reference.previewScale)
        put("regionX", reference.region.x)
        put("regionY", reference.region.y)
        put("hasTiles", tiles != null)
        tiles?.let { grid ->
            put(
                "tiles",
                buildJsonObject {
                    put("columns", grid.columns)
                    put("rows", grid.rows)
                    put("tileWidthPx", grid.tileWidthPx)
                    put("tileHeightPx", grid.tileHeightPx)
                    put("overlapPx", grid.overlapPx)
                },
            )
        }
        put("hint", "Open the file at path to see the frame; use computer_zoom for a native-resolution crop.")
    }.toString()

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
    ): AgentToolResult {
        if (action == null) return failure("InvalidAction")
        val session = authorizedSession(context) ?: return failure("CaptureChangedSinceApproval")
        val capture = context.authorization?.binding?.split(':')?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }?.let(::CaptureId)
        val armed = machine.send(ComputerUseIntent.Public.ArmInput(true, session, capture))
        if (armed != SendResult.Accepted) return failure("CaptureChangedSinceApproval")
        val output = awaitOutput(
            machine,
            ComputerUseIntent.Public.Input(
                action,
                expectedSession = session,
                expectedCapture = capture,
            ),
        )
            ?: return failure("InputTimedOut")
        return when (output) {
            is ComputerUseOutput.InputApplied -> AgentToolResult("Applied $name")

            is ComputerUseOutput.Rejected -> failure(output.reason.name)

            is ComputerUseOutput.FrameReady, is ComputerUseOutput.CaptureChanged,
            is ComputerUseOutput.PermissionRequired, is ComputerUseOutput.SessionClosed, ComputerUseOutput.Revoked,
            -> failure("UnexpectedOutput")
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
            val sent = machine.send(ComputerUseIntent.Public.CancelSession(session))
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

    /** Subscribes before sending and matches the unique operation ID, including rejected responses. */
    private suspend fun awaitOutput(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        intent: ComputerUseIntent.Public,
    ): ComputerUseOutput? = coroutineScope {
        val session = (machine.state.value as? ComputerUseState.Capturing)?.session
        val id = Uuid.random().toString()
        val correlated = when (intent) {
            is ComputerUseIntent.Public.Capture -> intent.copy(requestId = id)

            is ComputerUseIntent.Public.Crop -> intent.copy(requestId = id)

            is ComputerUseIntent.Public.Input -> intent.copy(requestId = id)

            ComputerUseIntent.Public.Start, ComputerUseIntent.Public.Retry,
            ComputerUseIntent.Public.RefreshTargets, ComputerUseIntent.Public.EndCapture,
            ComputerUseIntent.Public.Revoke, is ComputerUseIntent.Public.ArmInput,
            is ComputerUseIntent.Public.BeginCapture, is ComputerUseIntent.Public.CancelSession,
            is ComputerUseIntent.Public.SwitchMode, is ComputerUseIntent.Public.OwnerReleased,
            -> error("Only operation intents have correlated replies")
        }
        val awaited = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.first { it.requestId() == id }
        }
        var isCompleted = false
        try {
            if (machine.send(correlated) != SendResult.Accepted) {
                isCompleted = true
                return@coroutineScope ComputerUseOutput.Rejected(ignoredReason(machine.state.value, intent), id)
            }
            val result = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) { awaited.await() }
            isCompleted = result != null
            result
        } finally {
            awaited.cancel()
            if (!isCompleted && session != null) {
                // Cancel state effects as well as the waiter, without touching any replacement session.
                withContext(NonCancellable) { machine.send(ComputerUseIntent.Public.CancelSession(session)) }
            }
        }
    }

    private fun ignoredReason(state: ComputerUseState, intent: ComputerUseIntent.Public): ComputerUseFailure =
        if (intent is ComputerUseIntent.Public.Input && state is ComputerUseState.Capturing) {
            if (!state.isInputArmed) ComputerUseFailure.NotArmed else ComputerUseFailure.ModeNotAllowed
        } else {
            ComputerUseFailure.Unavailable
        }

    private fun ComputerUseOutput.requestId(): String? = when (this) {
        is ComputerUseOutput.FrameReady -> requestId

        is ComputerUseOutput.Rejected -> requestId

        is ComputerUseOutput.InputApplied -> requestId

        is ComputerUseOutput.CaptureChanged, is ComputerUseOutput.PermissionRequired,
        is ComputerUseOutput.SessionClosed, ComputerUseOutput.Revoked,
        -> null
    }

    private suspend fun mode(arguments: JsonObject): ComputerUseMode? {
        val name = arguments.text("mode")?.lowercase() ?: return null
        return when (name) {
            "desktop" -> ComputerUseMode.Desktop(isCursorIncluded = arguments.flag("includeCursor") ?: true)
            "window" -> windowMode(arguments)
            else -> null
        }
    }

    /** Resolves the requested window identity against the live window list. */
    private suspend fun windowMode(arguments: JsonObject): ComputerUseMode? {
        val identifier = arguments.text("windowId")?.let { WindowId(it) }
        val target = identifier?.let { id -> control.windows().firstOrNull { it.id == id } }
        return target?.let { ComputerUseMode.Window(it, isClientAreaOnly = arguments.flag("clientAreaOnly") ?: false) }
    }

    private fun click(arguments: JsonObject): InputAction? {
        val point = point(arguments, "x", "y") ?: return null
        return InputAction.Click(
            point = point,
            button = button(arguments.text("button")),
            count = (arguments.int("count") ?: 1).coerceIn(1, MAX_CLICKS),
            space = space(arguments.text("space")),
        )
    }

    private fun drag(arguments: JsonObject): InputAction? {
        val from = point(arguments, "x", "y") ?: return null
        val to = point(arguments, "toX", "toY") ?: return null
        return InputAction.Drag(from, to, button(arguments.text("button")), space(arguments.text("space")))
    }

    private fun scroll(arguments: JsonObject): InputAction? {
        val at = point(arguments, "x", "y") ?: return null
        return InputAction.Scroll(
            point = at,
            deltaX = arguments.int("deltaX") ?: 0,
            deltaY = arguments.int("deltaY") ?: 0,
            space = space(arguments.text("space")),
        )
    }

    private fun typed(arguments: JsonObject): InputAction? {
        val text = arguments.text("text") ?: return null
        if (text.length > MAX_TYPED_CHARS) return null
        return InputAction.Type(text)
    }

    private fun keys(arguments: JsonObject): InputAction? {
        val names = arguments["keys"]?.jsonPrimitive?.content?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (names.isNullOrEmpty() || names.size > MAX_KEYS) return null
        return InputAction.Key(names)
    }

    private fun point(arguments: JsonObject, xName: String, yName: String): FramePoint? {
        val x = arguments.number(xName) ?: return null
        val y = arguments.number(yName) ?: return null
        return FramePoint(x, y)
    }

    private fun region(arguments: JsonObject): CaptureRegion? {
        val x = arguments.int("regionX") ?: return null
        val y = arguments.int("regionY") ?: return null
        val width = arguments.int("regionWidth") ?: return null
        val height = arguments.int("regionHeight") ?: return null
        return try {
            CaptureRegion(x, y, width, height)
        } catch (e: IllegalArgumentException) {
            log.w(e) { "region refused" }
            null
        }
    }

    private fun normalized(arguments: JsonObject): NormalizedRegion? {
        val x = arguments.number("nx") ?: return null
        val y = arguments.number("ny") ?: return null
        val width = arguments.number("nw") ?: return null
        val height = arguments.number("nh") ?: return null
        return try {
            NormalizedRegion(x, y, width, height)
        } catch (e: IllegalArgumentException) {
            log.w(e) { "normalized region refused" }
            null
        }
    }

    private fun encoding(arguments: JsonObject, default: CaptureEncoding): CaptureEncoding {
        val preset = arguments.text("preset")?.let { CapturePresets.byName(it) } ?: default
        val format = format(arguments.text("format")) ?: preset.format
        val quality = (arguments.int("quality") ?: preset.quality).coerceIn(MIN_QUALITY, MAX_QUALITY)
        val maxWidth = (arguments.int("maxWidth") ?: preset.maxWidthPx).coerceAtLeast(0)
        val maxBytes = (arguments.int("maxBytes") ?: preset.maxBytes).coerceAtLeast(0)
        return preset.copy(
            format = format,
            quality = quality,
            maxWidthPx = maxWidth,
            maxHeightPx = if (maxWidth > 0) maxWidth else preset.maxHeightPx,
            maxBytes = maxBytes,
        )
    }

    private fun format(name: String?): CaptureFormat? = when (name?.lowercase()) {
        "png" -> CaptureFormat.Png
        "jpg", "jpeg" -> CaptureFormat.Jpeg
        null -> null
        else -> null
    }

    private fun button(name: String?): MouseButton = when (name?.lowercase()) {
        "right" -> MouseButton.Right
        "middle" -> MouseButton.Middle
        else -> MouseButton.Left
    }

    private fun space(name: String?): FrameSpace = when (name?.lowercase()) {
        "master" -> FrameSpace.Master
        "normalized" -> FrameSpace.Normalized
        "screen" -> FrameSpace.Screen
        else -> FrameSpace.Preview
    }

    private fun describe(name: String, arguments: JsonObject, xName: String, yName: String): String {
        val x = arguments.number(xName)
        val y = arguments.number(yName)
        val button = arguments.text("button") ?: "left"
        return "Apply $button $name at ${x ?: "?"},${y ?: "?"} in ${arguments.text("space") ?: "preview"} space"
    }

    private fun describeText(text: String): String {
        val escaped = visible(text)
        val suffix = if (escaped.length > MAX_APPROVAL_CHARS) "… (preview truncated)" else ""
        return "Type ${text.length} characters: ${escaped.take(MAX_APPROVAL_CHARS)}$suffix"
    }

    private fun visible(value: String?): String = buildString {
        value.orEmpty().forEach { character ->
            append(
                when (character) {
                    '\n' -> "\\n"

                    '\r' -> "\\r"

                    '\t' -> "\\t"

                    '\b' -> "\\b"

                    '\\' -> "\\\\"

                    else -> if (character < FIRST_VISIBLE_CHAR || character == DELETE_CHAR) {
                        "\\u${character.code.toString(HEX_RADIX).padStart(UNICODE_DIGITS, '0')}"
                    } else {
                        character.toString()
                    }
                },
            )
        }
    }

    private fun JsonObject.hasAny(keys: Set<String>): Boolean = keys.any { it in this }

    private fun failure(code: String): AgentToolResult = AgentToolResult(code, isError = true)

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.raw(name: String): String? = this[name]?.toString()
    private fun JsonObject.flag(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.number(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

    private companion object {
        val regionKeys = setOf("regionX", "regionY", "regionWidth", "regionHeight")
        val normalizedKeys = setOf("nx", "ny", "nw", "nh")
        val mutatingTools = setOf(CAPTURE_TOOL, CLICK_TOOL, DRAG_TOOL, SCROLL_TOOL, TYPE_TOOL, KEY_TOOL, RELEASE_TOOL)
        const val STATUS_TOOL = "computer_status"
        const val WINDOWS_TOOL = "computer_windows"
        const val CAPTURE_TOOL = "computer_capture"
        const val SCREENSHOT_TOOL = "computer_screenshot"
        const val ZOOM_TOOL = "computer_zoom"
        const val CLICK_TOOL = "computer_click"
        const val DRAG_TOOL = "computer_drag"
        const val SCROLL_TOOL = "computer_scroll"
        const val TYPE_TOOL = "computer_type"
        const val KEY_TOOL = "computer_key"
        const val RELEASE_TOOL = "computer_release"
        const val START_TIMEOUT_MILLIS = 5_000L
        const val CAPTURE_TIMEOUT_MILLIS = 10_000L
        const val OUTPUT_TIMEOUT_MILLIS = 30_000L
        const val MAX_WINDOWS = 60
        const val MAX_CLICKS = 3
        const val MAX_KEYS = 6
        const val MAX_TYPED_CHARS = 4096
        const val MAX_APPROVAL_CHARS = 512
        const val BINDING_FIELDS = 3
        const val HEX_RADIX = 16
        const val UNICODE_DIGITS = 4
        const val MIN_QUALITY = 1
        const val MAX_QUALITY = 100
        const val OVERVIEW_WIDTH_PX = 1568
        const val FIRST_VISIBLE_CHAR = ' '
        const val DELETE_CHAR = '\u007F'

        val EmptySchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{},"additionalProperties":false}""",
        ).jsonObject

        val toolSpecs = listOf(
            AgentToolSpec(STATUS_TOOL, "Read capture availability, mode, permissions and input arming.", EmptySchema),
            AgentToolSpec(WINDOWS_TOOL, "List capturable windows with their identifiers and bounds.", EmptySchema),
            AgentToolSpec(
                CAPTURE_TOOL,
                "Start or switch the capture: mode \"desktop\" or \"window\" with windowId; returns the first frame.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{
                        "mode":{"type":"string","enum":["desktop","window"]},
                        "windowId":{"type":"string"},
                        "clientAreaOnly":{"type":"boolean"},
                        "includeCursor":{"type":"boolean"}
                    },"required":["mode"],"additionalProperties":false}""",
                ).jsonObject,
            ),
            AgentToolSpec(
                SCREENSHOT_TOOL,
                "Capture one reduced frame of the active session; returns a stored file path and its geometry.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{
                        "preset":{"type":"string","enum":["overview","text","detail","ui"]},
                        "format":{"type":"string","enum":["png","jpeg"]},
                        "quality":{"type":"integer","minimum":1,"maximum":100},
                        "maxWidth":{"type":"integer","minimum":0,"maximum":4096},
                        "maxBytes":{"type":"integer","minimum":0},
                        "tile":{"type":"string"},
                        "regionX":{"type":"integer"},"regionY":{"type":"integer"},
                        "regionWidth":{"type":"integer"},"regionHeight":{"type":"integer"},
                        "fresh":{"type":"boolean"},"includeCursor":{"type":"boolean"}
                    },"additionalProperties":false}""",
                ).jsonObject,
            ),
            AgentToolSpec(
                ZOOM_TOOL,
                "Cut a native-resolution crop out of a stored master frame; accepts master pixels or fractions.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{
                        "captureId":{"type":"string"},"tile":{"type":"string"},
                        "regionX":{"type":"integer"},"regionY":{"type":"integer"},
                        "regionWidth":{"type":"integer"},"regionHeight":{"type":"integer"},
                        "nx":{"type":"number"},"ny":{"type":"number"},"nw":{"type":"number"},"nh":{"type":"number"},
                        "scale":{"type":"number","minimum":1,"maximum":4},
                        "format":{"type":"string","enum":["png","jpeg"]}
                    },"additionalProperties":false}""",
                ).jsonObject,
            ),
            AgentToolSpec(
                CLICK_TOOL,
                "Click inside the captured area; coordinates are pixels of the frame you received.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{
                        "x":{"type":"number"},"y":{"type":"number"},
                        "button":{"type":"string","enum":["left","right","middle"]},
                        "count":{"type":"integer","minimum":1,"maximum":3},
                        "space":{"type":"string","enum":["preview","master","normalized","screen"]}
                    },"required":["x","y"],"additionalProperties":false}""",
                ).jsonObject,
                AgentToolAction.Command,
            ),
            AgentToolSpec(
                DRAG_TOOL,
                "Drag between two points inside the captured area.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{
                        "x":{"type":"number"},"y":{"type":"number"},"toX":{"type":"number"},"toY":{"type":"number"},
                        "button":{"type":"string","enum":["left","right","middle"]},
                        "space":{"type":"string","enum":["preview","master","normalized","screen"]}
                    },"required":["x","y","toX","toY"],"additionalProperties":false}""",
                ).jsonObject,
                AgentToolAction.Command,
            ),
            AgentToolSpec(
                SCROLL_TOOL,
                "Scroll at a point; deltaY is negative when scrolling up.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{
                        "x":{"type":"number"},"y":{"type":"number"},
                        "deltaX":{"type":"integer"},"deltaY":{"type":"integer"},
                        "space":{"type":"string","enum":["preview","master","normalized","screen"]}
                    },"required":["x","y","deltaY"],"additionalProperties":false}""",
                ).jsonObject,
                AgentToolAction.Command,
            ),
            AgentToolSpec(
                TYPE_TOOL,
                "Type text into the focused control of the captured area.",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{"text":{"type":"string"}},
                       "required":["text"],"additionalProperties":false}""",
                ).jsonObject,
                AgentToolAction.Command,
            ),
            AgentToolSpec(
                KEY_TOOL,
                "Press a key combination, e.g. \"ctrl,s\" or \"f5\" or \"enter\".",
                Json.parseToJsonElement(
                    """{"type":"object","properties":{"keys":{"type":"string"}},
                       "required":["keys"],"additionalProperties":false}""",
                ).jsonObject,
                AgentToolAction.Command,
            ),
            AgentToolSpec(
                RELEASE_TOOL,
                "Stop the capture, disarm input and delete the stored frames of this session.",
                EmptySchema,
                AgentToolAction.Command,
            ),
        )
    }
}
