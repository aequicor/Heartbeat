package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import kotlinx.serialization.json.JsonObject

// Approval texts of the hosted computer tools: the exact action, with control characters escaped and text bounded.

private const val MAX_APPROVAL_CHARS = 512
private const val HEX_RADIX = 16
private const val UNICODE_DIGITS = 4
private const val FIRST_VISIBLE_CHAR = ' '
private const val DELETE_CHAR = '\u007F'

/** What the user approves for [spec] called with [arguments]. */
internal fun approvalDescription(spec: AgentToolSpec, arguments: JsonObject): String {
    val tool = ComputerTool.parse(spec.name) as? ComputerTool.Family
    return when (tool?.verb) {
        ToolVerb.Click -> describe(spec.name, arguments, "x", "y")

        ToolVerb.Drag -> "${describe(spec.name, arguments, "x", "y")} to " +
            "${arguments.raw("toX").orEmpty()},${arguments.raw("toY").orEmpty()}"

        ToolVerb.Scroll -> "${describe(spec.name, arguments, "x", "y")} " +
            "delta=${arguments.raw("deltaX").orEmpty()},${arguments.raw("deltaY").orEmpty()}"

        ToolVerb.Type -> describeText(arguments.text("text").orEmpty())

        ToolVerb.Key -> "Press ${visible(arguments.raw("keys"))}"

        ToolVerb.Capture -> describeCapture(tool.family, arguments)

        ToolVerb.Release -> "Stop the capture and delete its stored frames"

        ToolVerb.Screenshot, ToolVerb.Zoom, ToolVerb.Roi, null -> spec.description
    }
}

private fun describeCapture(family: ToolFamily, arguments: JsonObject): String = when (family) {
    ToolFamily.Desktop ->
        "Start capturing the desktop monitorId=${visible(arguments.text("monitorId") ?: "all")} " +
            "includeCursor=${arguments.flag("includeCursor") ?: true}"

    ToolFamily.Window ->
        "Start capturing window windowId=${visible(arguments.text("windowId"))} " +
            "clientAreaOnly=${arguments.flag("clientAreaOnly") ?: false} " +
            "includeCursor=${arguments.flag("includeCursor") ?: true}"
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
