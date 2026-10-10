package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Declared before the specifications: file-level values initialize in source order.
private val ATTACH_SCOPES = listOf("attached", "profile", "project")
private val ITEM_KINDS = listOf("skill", "instruction", "template", "script", "workflow")

/**
 * Library edits are Read in the trust table: the profile approval level adds the decision instead, and code or
 * native enabling always asks. Declarations never change while the feature is on.
 */
internal val HARNESS_AUTHORING_SPECS = listOf(
    AgentToolSpec(
        HarnessTools.CREATE,
        "Create a reusable harness: a named environment of skills, instructions, templates, scripts, workflows and " +
            "tool policy for a task or project. The user may have to approve it.",
        schema(required = listOf("name", "title")) {
            text("name", "Immutable slug [a-z][a-z0-9_]{1,19}, e.g. compose_ui")
            text("title", "Short single-line title")
            text("description", "When the harness applies, single line")
            choice(
                "scope",
                "attached (default): only connected chats; profile: everywhere; project: this project",
                ATTACH_SCOPES,
            )
            flag("attach", "Connect the harness to this chat (default true)")
        },
    ),
    AgentToolSpec(
        HarnessTools.UPDATE,
        "Change the title, description or permanent scope of a harness. The slug never changes.",
        schema(required = listOf("harness")) {
            text("harness", "Harness slug")
            text("title", "New single-line title")
            text("description", "New single-line description; empty clears it")
            choice("scope", "attached, profile or project (the source project of this chat)", ATTACH_SCOPES)
        },
    ),
    AgentToolSpec(
        HarnessTools.DELETE,
        "Delete a harness with all its items; its running workflows are cancelled.",
        harnessOnly(),
    ),
    AgentToolSpec(HarnessTools.ATTACH, "Connect a harness to this chat.", harnessOnly()),
    AgentToolSpec(HarnessTools.DETACH, "Disconnect a harness from this chat.", harnessOnly()),
    AgentToolSpec(
        HarnessTools.ITEM_PUT,
        "Add or replace a harness item by name. Kotlin scripts and workflows are compiled first and always need the " +
            "user's approval; read harness_api_reference before writing code. Never include secrets.",
        schema(required = listOf("harness", "kind", "name", "content")) {
            text("harness", "Harness slug")
            choice("kind", "skill, instruction, template, script or workflow", ITEM_KINDS)
            text("name", "Immutable item slug [a-z][a-z0-9_]{0,31}")
            text("description", "When to use it; required for skills and templates")
            text("content", "Markdown body, instruction text, template with {{arg}} placeholders, or Kotlin source")
            putJsonObject("input") {
                put("type", "object")
                put("description", "Workflow input schema: object with string/number/boolean/enum properties")
            }
        },
    ),
    AgentToolSpec(
        HarnessTools.ITEM_DELETE,
        "Delete one harness item by name; running workflows keep their pinned copy.",
        schema(required = listOf("harness", "name")) {
            text("harness", "Harness slug")
            text("name", "Item slug")
        },
    ),
    AgentToolSpec(
        HarnessTools.TOOLS_SET,
        "Replace the tool policy of a harness: Heartbeat tools to turn off and native engine tools to switch on or " +
            "off (omitted tools keep their defaults). Read harness_tools_catalog first. Enabling a native tool " +
            "always needs the user's approval.",
        schema(required = listOf("harness")) {
            text("harness", "Harness slug")
            putJsonObject("hosted_off") {
                put("type", "array")
                put("description", "Heartbeat tool names to turn off")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("native") {
                put("type", "object")
                put("description", "Engine id → { native tool name → \"on\" | \"off\" }")
                putJsonObject("additionalProperties") {
                    put("type", "object")
                    putJsonObject("additionalProperties") {
                        put("type", "string")
                        put("enum", JsonArray(listOf(JsonPrimitive("on"), JsonPrimitive("off"))))
                    }
                }
            }
        },
    ),
)

/** Reads of the library and its reference; they never change durable state. */
internal val HARNESS_READ_SPECS = listOf(
    AgentToolSpec(
        HarnessTools.LIST,
        "List harnesses of the profile with scope, state and which are active here.",
        schema(),
    ),
    AgentToolSpec(
        HarnessTools.GET,
        "Read a harness: metadata, items with status, tool policy; with item, the full text or source of that item.",
        schema(required = listOf("harness")) {
            text("harness", "Harness slug")
            text("item", "Optional item slug to read in full")
        },
    ),
    AgentToolSpec(
        HarnessTools.TOOLS_CATALOG,
        "List Heartbeat tool groups and native tools of every engine with defaults, whether they can be enabled and " +
            "their current state in this chat.",
        schema(),
    ),
    AgentToolSpec(
        HarnessTools.API_REFERENCE,
        "Harness authoring reference: guide (what to build), api (Kotlin script and workflow API), events, examples.",
        schema(required = listOf("topic")) {
            choice("topic", "guide, api, events or examples", listOf("guide", "api", "events", "examples"))
        },
    ),
)

/** Workflow runs of the caller. Starting is a Command: it asks unless the turn has Full trust. */
internal val HARNESS_WORKFLOW_SPECS = listOf(
    AgentToolSpec(
        HarnessTools.WORKFLOW_START,
        "Start a harness workflow with helper chats. This chat is woken with the result when it finishes; otherwise " +
            "poll harness_workflow_status.",
        schema(required = listOf("harness", "workflow")) {
            text("harness", "Harness slug")
            text("workflow", "Workflow item slug")
            putJsonObject("input") {
                put("type", "object")
                put("description", "Input matching the workflow schema")
            }
        },
        AgentToolAction.Command,
    ),
    AgentToolSpec(
        HarnessTools.WORKFLOW_STATUS,
        "Read the status, steps and result of a workflow run started by this chat.",
        schema(required = listOf("run")) { text("run", "Run id wf_…") },
    ),
    AgentToolSpec(
        HarnessTools.WORKFLOW_CANCEL,
        "Cancel a workflow run started by this chat and stop its helpers.",
        schema(required = listOf("run")) { text("run", "Run id wf_…") },
    ),
)

private fun harnessOnly(): JsonObject = schema(required = listOf("harness")) { text("harness", "Harness slug") }

private fun schema(required: List<String> = emptyList(), properties: JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties", properties)
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }

private fun JsonObjectBuilder.text(name: String, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
    }
}

private fun JsonObjectBuilder.flag(name: String, description: String) {
    putJsonObject(name) {
        put("type", "boolean")
        put("description", description)
    }
}

private fun JsonObjectBuilder.choice(name: String, description: String, values: List<String>) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }
}
