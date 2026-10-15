package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.impl.domain.MAX_TYPED_CHARS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Two tool families drive the two capture modes: `computer_*` works on the whole desktop and `window_*` on one
 * application window. Each family opens its own capture; its frame and input tools refuse a capture of the other mode.
 */
internal enum class ToolFamily(val prefix: String, val area: String) {
    Desktop("computer", "the captured desktop"),
    Window("window", "the captured window"),
    ;

    /** `true` when an active capture in [mode] belongs to this family. */
    fun owns(mode: ComputerUseMode): Boolean = when (this) {
        Desktop -> mode is ComputerUseMode.Desktop
        Window -> mode is ComputerUseMode.Window
    }

    fun tool(verb: ToolVerb): String = "${prefix}_${verb.suffix}"

    companion object {
        fun of(mode: ComputerUseMode): ToolFamily = when (mode) {
            is ComputerUseMode.Desktop -> Desktop
            is ComputerUseMode.Window -> Window
        }
    }
}

/** What a family tool does; every family declares the same verbs. */
internal enum class ToolVerb(val suffix: String, val isInput: Boolean = false, val isRecording: Boolean = false) {
    Capture("capture"),
    Screenshot("screenshot"),
    Zoom("zoom"),
    Roi("roi"),
    Record("record", isRecording = true),
    Click("click", isInput = true),
    Drag("drag", isInput = true),
    Scroll("scroll", isInput = true),
    Type("type", isInput = true),
    Key("key", isInput = true),
    Release("release"),
}

/** One parsed tool name. */
internal sealed interface ComputerTool {
    /** `computer_status`: shared by both families. */
    data object Status : ComputerTool

    /** `window_list`: the capturable windows. */
    data object WindowList : ComputerTool

    /** A verb of one family. */
    data class Family(val family: ToolFamily, val verb: ToolVerb) : ComputerTool {
        val name: String get() = family.tool(verb)
    }

    companion object {
        fun parse(name: String): ComputerTool? = when (name) {
            STATUS_TOOL -> Status

            WINDOW_LIST_TOOL -> WindowList

            else -> ToolFamily.entries.firstNotNullOfOrNull { family ->
                ToolVerb.entries.firstOrNull { family.tool(it) == name }?.let { Family(family, it) }
            }
        }
    }
}

internal const val STATUS_TOOL = "computer_status"
internal const val WINDOW_LIST_TOOL = "window_list"
internal const val OVERVIEW_WIDTH_PX = 1568

private val EmptySchema: JsonObject = schema("""{"type":"object","properties":{},"additionalProperties":false}""")

private const val ROI_PROPERTY =
    """"roi":{"type":"object","description":"ROI (rectangle of interest); space: preview (pixels of the """ +
        """frame you last received, default), master or normalized (0..1)","properties":{"x":{"type":"number"},""" +
        """"y":{"type":"number"},"width":{"type":"number"},"height":{"type":"number"},"space":{"type":"string",""" +
        """"enum":["preview","master","normalized"]}},"required":["x","y","width","height"],""" +
        """"additionalProperties":false}"""
private const val TILE_PROPERTY =
    """"tile":{"type":"string","description":"Zero-based column:row of the tile grid over the active ROI, """ +
        """for example 0:0"}"""
private const val SPACE_PROPERTY = """"space":{"type":"string","enum":["preview","master","normalized","screen"]}"""
private const val BUTTON_PROPERTY = """"button":{"type":"string","enum":["left","right","middle"]}"""

/** Every hosted computer tool, the shared status first; recording adds `<family>_record` and `observeMs`. */
internal fun computerToolSpecs(isRecordingEnabled: Boolean): List<AgentToolSpec> =
    if (isRecordingEnabled) recordingSpecs else plainSpecs

// Lazy: the schemas below are top-level values initialized after this point of the file.
private val recordingSpecs: List<AgentToolSpec> by lazy { allSpecs(isRecordingEnabled = true) }
private val plainSpecs: List<AgentToolSpec> by lazy { allSpecs(isRecordingEnabled = false) }

private fun allSpecs(isRecordingEnabled: Boolean): List<AgentToolSpec> = buildList {
    add(
        AgentToolSpec(
            STATUS_TOOL,
            "Read permissions, monitors, the active capture family, its ROI and input arming.",
            EmptySchema,
        ),
    )
    addAll(familySpecs(ToolFamily.Desktop, isRecordingEnabled))
    add(
        AgentToolSpec(
            WINDOW_LIST_TOOL,
            "List capturable application windows with their identifiers and bounds.",
            EmptySchema,
        ),
    )
    addAll(familySpecs(ToolFamily.Window, isRecordingEnabled))
}

private fun familySpecs(family: ToolFamily, isRecordingEnabled: Boolean): List<AgentToolSpec> =
    ToolVerb.entries.filter { isRecordingEnabled || !it.isRecording }.map { verb ->
        familySpec(family, verb, isRecordingEnabled)
    }

private fun familySpec(family: ToolFamily, verb: ToolVerb, isRecordingEnabled: Boolean): AgentToolSpec = AgentToolSpec(
    name = family.tool(verb),
    description = description(family, verb),
    inputSchema = inputSchema(family, verb).let { if (isRecordingEnabled && verb.isInput) it.observed() else it },
    action = if (verb.isInput || verb == ToolVerb.Release) AgentToolAction.Command else AgentToolAction.Read,
)

/** Adds `observeMs`: after the input, record until the screen settles and return the keyframes. */
private fun JsonObject.observed(): JsonObject {
    val properties = getValue("properties").jsonObject + (OBSERVE_ARGUMENT to OBSERVE_SCHEMA)
    return JsonObject(this + ("properties" to JsonObject(properties)))
}

internal const val OBSERVE_ARGUMENT = "observeMs"
internal const val MAX_OBSERVE_MILLIS = 5_000L

private val OBSERVE_SCHEMA = schema(
    """{"type":"integer","minimum":0,"maximum":$MAX_OBSERVE_MILLIS,
       "description":"After the input, record up to this long until the screen settles; returns keyframes"}""",
)

private fun description(family: ToolFamily, verb: ToolVerb): String = when (verb) {
    ToolVerb.Capture -> when (family) {
        ToolFamily.Desktop -> "Start capturing the whole desktop (or one monitor); returns the first frame."
        ToolFamily.Window -> "Start capturing one application window (windowId from window_list); returns a frame."
    }

    ToolVerb.Screenshot -> "Capture one reduced frame of the active ROI of ${family.area} with its geometry."

    ToolVerb.Zoom -> "Cut a native-resolution crop inside the active ROI of ${family.area} from a stored master."

    ToolVerb.Roi ->
        "Set or clear (clear:true) the ROI (rectangle of interest) of ${family.area}: frames and input " +
            "stay inside it and frame pixels are relative to it; returns a frame of the new ROI."

    ToolVerb.Click -> "Click inside the ROI of ${family.area}; coordinates are pixels of the frame you received."

    ToolVerb.Drag -> "Drag between two points inside the ROI of ${family.area}."

    ToolVerb.Scroll -> "Scroll at a point inside the ROI of ${family.area}; deltaY is negative when scrolling up."

    ToolVerb.Type ->
        "Type text into the focused control of ${family.area}, at most $MAX_TYPED_CHARS UTF-16 code " +
            "units (a character outside the BMP counts as two)."

    ToolVerb.Key -> "Press a key combination in ${family.area}, e.g. \"ctrl,s\" or \"f5\" or \"enter\"."

    ToolVerb.Release -> "Stop the capture, disarm input and delete the stored frames of this session."

    ToolVerb.Record ->
        "Record ${family.area} for a short time (video mode): returns keyframes cropped to the area " +
            "that changed inside the ROI, until the screen settles or the time limit."
}

private fun inputSchema(family: ToolFamily, verb: ToolVerb): JsonObject = when (verb) {
    ToolVerb.Capture -> captureSchema(family)
    ToolVerb.Record -> RecordSchema
    ToolVerb.Screenshot, ToolVerb.Zoom, ToolVerb.Roi -> frameSchema(verb)
    ToolVerb.Click, ToolVerb.Drag, ToolVerb.Scroll, ToolVerb.Type, ToolVerb.Key -> inputActionSchema(verb)
    ToolVerb.Release -> EmptySchema
}

private fun captureSchema(family: ToolFamily): JsonObject = when (family) {
    ToolFamily.Desktop -> schema(
        """{"type":"object","properties":{
                "monitorId":{"type":"string","description":"Monitor id from computer_status; omit for every monitor"},
                "includeCursor":{"type":"boolean"}
            },"additionalProperties":false}""",
    )

    ToolFamily.Window -> schema(
        """{"type":"object","properties":{
                "windowId":{"type":"string"},
                "clientAreaOnly":{"type":"boolean"},
                "includeCursor":{"type":"boolean"}
            },"required":["windowId"],"additionalProperties":false}""",
    )
}

private fun frameSchema(verb: ToolVerb): JsonObject = when (verb) {
    ToolVerb.Screenshot -> schema(
        """{"type":"object","properties":{
            "preset":{"type":"string","enum":["overview","text","detail","ui"]},
            "format":{"type":"string","enum":["png","jpeg"]},
            "quality":{"type":"integer","minimum":1,"maximum":100},
            "maxWidth":{"type":"integer","minimum":0,"maximum":4096},
            "maxBytes":{"type":"integer","minimum":0},
            $TILE_PROPERTY,$ROI_PROPERTY,
            "fresh":{"type":"boolean"},"includeCursor":{"type":"boolean"}
        },"additionalProperties":false}""",
    )

    ToolVerb.Zoom -> schema(
        """{"type":"object","properties":{
            "captureId":{"type":"string"},$TILE_PROPERTY,$ROI_PROPERTY,
            "scale":{"type":"number","minimum":1,"maximum":4},
            "format":{"type":"string","enum":["png","jpeg"]}
        },"additionalProperties":false}""",
    )

    ToolVerb.Roi -> schema(
        """{"type":"object","properties":{$ROI_PROPERTY,$TILE_PROPERTY,"clear":{"type":"boolean"}},
           "additionalProperties":false}""",
    )

    ToolVerb.Capture, ToolVerb.Click, ToolVerb.Drag, ToolVerb.Scroll, ToolVerb.Type, ToolVerb.Key, ToolVerb.Release,
    ToolVerb.Record,
    -> EmptySchema
}

private fun inputActionSchema(verb: ToolVerb): JsonObject = when (verb) {
    ToolVerb.Click -> schema(
        """{"type":"object","properties":{
            "x":{"type":"number"},"y":{"type":"number"},$BUTTON_PROPERTY,
            "count":{"type":"integer","minimum":1,"maximum":3},$SPACE_PROPERTY
        },"required":["x","y"],"additionalProperties":false}""",
    )

    ToolVerb.Drag -> schema(
        """{"type":"object","properties":{
            "x":{"type":"number"},"y":{"type":"number"},"toX":{"type":"number"},"toY":{"type":"number"},
            $BUTTON_PROPERTY,$SPACE_PROPERTY
        },"required":["x","y","toX","toY"],"additionalProperties":false}""",
    )

    ToolVerb.Scroll -> schema(
        """{"type":"object","properties":{
            "x":{"type":"number"},"y":{"type":"number"},
            "deltaX":{"type":"integer"},"deltaY":{"type":"integer"},$SPACE_PROPERTY
        },"required":["x","y","deltaY"],"additionalProperties":false}""",
    )

    ToolVerb.Type -> schema(
        """{"type":"object","properties":{"text":{"type":"string","maxLength":$MAX_TYPED_CHARS}},
           "required":["text"],"additionalProperties":false}""",
    )

    ToolVerb.Key -> schema(
        """{"type":"object","properties":{"keys":{"type":"string"}},
           "required":["keys"],"additionalProperties":false}""",
    )

    ToolVerb.Capture, ToolVerb.Screenshot, ToolVerb.Zoom, ToolVerb.Roi, ToolVerb.Release, ToolVerb.Record,
    -> EmptySchema
}

private val RecordSchema: JsonObject = schema(
    """{"type":"object","properties":{
        "fps":{"type":"integer","minimum":1,"maximum":10},
        "durationMs":{"type":"integer","minimum":200,"maximum":10000},
        "untilStable":{"type":"boolean"},
        "maxFrames":{"type":"integer","minimum":2,"maximum":6}
    },"additionalProperties":false}""",
)

private fun schema(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
