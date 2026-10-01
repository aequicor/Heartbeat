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
import io.aequicor.heartbeat.feature.computeruse.api.TileRef
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
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
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseAgentTools as AgentToolsToggle

/**
 * The hosted `computer_*` tools.
 *
 * Read-only tools return the path of a stored frame plus its geometry and token estimate; an engine that can
 * read files opens the frame itself. Mutating tools go through the machine, so the same guards apply whether the
 * command came from an agent or from the panel: input runs only while it is armed, and only inside the captured
 * area. Approvals show the exact action, with control characters escaped and the text bounded.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ComputerUseAgentTools(
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val control: HostComputerControl,
) : AgentToolContribution {
    private val log = Log.tag("ComputerUseAgentTools")

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (isEnabled()) toolSpecs else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String {
        if (!isEnabled()) return ""
        return "Computer use drives the real screen for testing and debugging. Call computer_status first: it " +
            "reports the permissions, the captured mode and whether input is armed. Capture the whole desktop " +
            "with computer_capture {mode:\"desktop\"} or one application window with " +
            "computer_capture {mode:\"window\", windowId} after picking an id from computer_windows. " +
            "computer_screenshot returns a downscaled frame (at most ${OVERVIEW_WIDTH_PX}px wide) and a tile grid; " +
            "read small text with computer_zoom on a region or a tile, which is cut from the same stored master " +
            "frame at native resolution. Pointer coordinates are pixels of the frame you last received unless " +
            "you pass space:\"master\", \"normalized\" or \"screen\". Input needs the user to arm it and passes " +
            "the confirmation gate; a refusal names the reason (NotArmed, RegionOutOfBounds, TargetClosed). " +
            "Call computer_release when the debugging step is done: the capture and its stored frames stay " +
            "alive until then or until the profile closes."
    }

    override fun approval(spec: AgentToolSpec, arguments: JsonObject): AgentToolApproval {
        val description = when (spec.name) {
            CLICK_TOOL, DRAG_TOOL, SCROLL_TOOL -> describe(spec.name, arguments, "x", "y")
            TYPE_TOOL -> "Type ${arguments.text("text")?.length ?: 0} characters into the captured area"
            KEY_TOOL -> "Press ${visible(arguments.raw("keys"))}"
            CAPTURE_TOOL -> "Start capturing ${visible(arguments.raw("mode"))}"
            RELEASE_TOOL -> "Stop the capture and delete its stored frames"
            else -> spec.description
        }
        return AgentToolApproval(spec.name, description.take(MAX_APPROVAL_CHARS))
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        try {
            if (!isEnabled()) return failure("Disabled")
            val machine = started() ?: return failure("ComputerUseUnavailable")
            dispatch(machine, context, name, arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "hosted computer tool failed name=$name" }
            failure("ComputerUseToolFailed")
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
        CLICK_TOOL -> input(machine, click(arguments), name)
        DRAG_TOOL -> input(machine, drag(arguments), name)
        SCROLL_TOOL -> input(machine, scroll(arguments), name)
        TYPE_TOOL -> input(machine, typed(arguments), name)
        KEY_TOOL -> input(machine, keys(arguments), name)
        RELEASE_TOOL -> release(machine)
        else -> failure("UnknownTool")
    }

    private suspend fun isEnabled(): Boolean = toggles.get(ComputerUseEnabled) && toggles.get(AgentToolsToggle)

    /** The machine is created lazily; a tool call is what starts the availability probe. */
    private suspend fun started(): MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>? {
        val machine = machines.find(ComputerUseMachineKey) ?: return null
        if (machine.state.value !is ComputerUseState.Idle) return machine
        machine.send(ComputerUseIntent.Public.Start)
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
            is ComputerUseState.Capturing -> ComputerUseIntent.Public.SwitchMode(mode, session)
            else -> ComputerUseIntent.Public.BeginCapture(mode, owner, session)
        }
        val sent = machine.send(intent)
        if (sent != SendResult.Accepted) {
            log.w { "capture refused result=$sent" }
            return failure("CaptureRefused")
        }
        withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) {
            machine.state.first { it is ComputerUseState.Capturing && it.session == session }
        } ?: return failure("CaptureTimedOut")
        return screenshot(machine, arguments)
    }

    private suspend fun screenshot(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        arguments: JsonObject,
    ): AgentToolResult {
        val request = CaptureRequest(
            region = region(arguments),
            tile = arguments.text("tile")?.let { TileRef.parse(it) },
            encoding = encoding(arguments, CapturePresets.AgentOverview),
            isCursorIncluded = arguments.flag("includeCursor") ?: true,
            isFresh = arguments.flag("fresh") ?: true,
        )
        return frame(machine, ComputerUseIntent.Public.Capture(request), request.encoding)
    }

    private suspend fun zoom(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        arguments: JsonObject,
    ): AgentToolResult {
        val state = machine.state.value as? ComputerUseState.Capturing ?: return failure("NotCapturing")
        val master = state.master ?: return failure("NoMasterFrame")
        val identifier = arguments.text("captureId")?.let { CaptureId(it) } ?: master.id
        val explicit = region(arguments)
        val normalized = normalized(arguments)
        val scale = arguments.number("scale") ?: 1.0
        val request = try {
            CropRequest(
                capture = identifier,
                region = explicit,
                normalized = normalized,
                scale = scale,
                encoding = encoding(arguments, CapturePresets.AgentDetail),
            )
        } catch (e: IllegalArgumentException) {
            log.w(e) { "crop request refused" }
            return failure("InvalidCrop")
        }
        return frame(machine, ComputerUseIntent.Public.Crop(request), request.encoding)
    }

    private suspend fun frame(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        intent: ComputerUseIntent.Public,
        encoding: CaptureEncoding,
    ): AgentToolResult {
        val output = awaitOutput(machine, intent) ?: return failure("FrameTimedOut")
        return when (output) {
            is ComputerUseOutput.FrameReady -> AgentToolResult(frameText(output.capture, output.tiles != null))
            is ComputerUseOutput.Rejected -> failure(output.reason.name)
            else -> failure("UnexpectedOutput")
        }.also { log.d { "frame served format=${encoding.format}" } }
    }

    private fun frameText(reference: CaptureRef, hasTiles: Boolean): String = buildJsonObject {
        put("captureId", reference.id.value)
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
        put("hasTiles", hasTiles)
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
        action: InputAction?,
        name: String,
    ): AgentToolResult {
        if (action == null) return failure("InvalidAction")
        val output = awaitOutput(machine, ComputerUseIntent.Public.Input(action))
            ?: return failure("InputTimedOut")
        return when (output) {
            is ComputerUseOutput.InputApplied -> AgentToolResult("Applied $name")
            is ComputerUseOutput.Rejected -> failure(output.reason.name)
            else -> failure("UnexpectedOutput")
        }
    }

    private suspend fun release(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
    ): AgentToolResult {
        machine.send(ComputerUseIntent.Public.ArmInput(false))
        val sent = machine.send(ComputerUseIntent.Public.EndCapture)
        return if (sent == SendResult.Accepted) {
            AgentToolResult("Capture stopped and its frames deleted")
        } else {
            failure("NotCapturing")
        }
    }

    /** Subscribes before sending, so a one-shot output cannot be missed between the two calls. */
    private suspend fun awaitOutput(
        machine: MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput>,
        intent: ComputerUseIntent.Public,
    ): ComputerUseOutput? = coroutineScope {
        val awaited = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.mapNotNull { output -> output.takeIf { it.isAnswerTo(intent) } }.first()
        }
        if (machine.send(intent) != SendResult.Accepted) {
            awaited.cancel()
            return@coroutineScope null
        }
        val result = withTimeoutOrNull(OUTPUT_TIMEOUT_MILLIS) { awaited.await() }
        if (result == null) awaited.cancel()
        result
    }

    private fun ComputerUseOutput.isAnswerTo(intent: ComputerUseIntent.Public): Boolean = when (this) {
        is ComputerUseOutput.FrameReady ->
            intent is ComputerUseIntent.Public.Capture ||
                intent is ComputerUseIntent.Public.Crop

        is ComputerUseOutput.Rejected -> true

        is ComputerUseOutput.InputApplied -> intent is ComputerUseIntent.Public.Input

        is ComputerUseOutput.CaptureChanged -> false

        is ComputerUseOutput.PermissionRequired -> false

        is ComputerUseOutput.SessionClosed -> false

        ComputerUseOutput.Revoked -> false
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

    private fun visible(value: String?): String =
        value.orEmpty().filter { it >= FIRST_VISIBLE_CHAR && it != DELETE_CHAR }.take(MAX_APPROVAL_CHARS)

    private fun failure(code: String): AgentToolResult = AgentToolResult(code, isError = true)

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.raw(name: String): String? = this[name]?.toString()
    private fun JsonObject.flag(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.number(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

    private companion object {
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
                        "captureId":{"type":"string"},
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
